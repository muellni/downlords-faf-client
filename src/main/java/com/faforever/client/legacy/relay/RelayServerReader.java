package com.faforever.client.legacy.relay;

import com.faforever.client.legacy.QDataInputStream;
import com.faforever.client.legacy.ServerWriter;
import com.faforever.client.legacy.proxy.Proxy;
import com.faforever.client.legacy.proxy.ProxyUtils;
import com.faforever.client.util.SocketAddressUtil;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;

import static com.faforever.client.legacy.relay.RelayServerCommand.CONNECT_TO_PEER;
import static com.faforever.client.legacy.relay.RelayServerCommand.JOIN_GAME;

class RelayServerReader implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final InputStream inputStream;
  private final Proxy proxyServer;
  private final FaDataOutputStream faOutputStream;
  private final ServerWriter serverWriter;
  private final Gson gson;

  private boolean stopped;
  private boolean p2pProxyEnabled;

  public RelayServerReader(InputStream inputStream, Proxy proxyServer, FaDataOutputStream faOutputStream, ServerWriter serverWriter) {
    this.inputStream = inputStream;
    this.proxyServer = proxyServer;
    this.faOutputStream = faOutputStream;
    this.serverWriter = serverWriter;

    gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapter(RelayServerCommand.class, new RelayServerCommandTypeAdapter())
        .create();
  }

  public void blockingRead() throws IOException {
    try (QDataInputStream dataInput = new QDataInputStream(new DataInputStream(new BufferedInputStream(inputStream)))) {
      while (!stopped) {
        dataInput.skipBlockSize();
        String message = dataInput.readQString();

        logger.debug("Object from server: {}", message);

        RelayServerMessage relayServerMessage = gson.fromJson(message, RelayServerMessage.class);

        dispatchServerCommand(relayServerMessage.key, relayServerMessage.commands);
      }
    } catch (EOFException e) {
      logger.info("Disconnected from FAF relay server (EOF)");
    }
  }

  private void dispatchServerCommand(RelayServerCommand command, List<Object> args) throws IOException {
    switch (command) {
      case PING:
        handlePing();
        break;
      case HOST_GAME:
        handleHostGame(command, args);
        break;
      case SEND_NAT_PACKET:
        handleSendNatPacket(command, args);
        break;
      case P2P_RECONNECT:
        handleP2pReconnect();
        break;
      case JOIN_GAME:
        handleJoinGame(command, args);
        break;
      case CONNECT_TO_PEER:
        handleConnectToPeer(command, args);
        break;
      case CREATE_LOBBY:
        handleCreateLobby(command, args);
        break;
      case CONNECT_TO_PROXY:
        handleConnectToProxy(command, args);
        break;
      case JOIN_PROXY:
        handleJoinProxy(command, args);
        break;

      default:
        throw new IllegalStateException("Unhandled relay server command: " + command);
    }
  }

  private void handleHostGame(RelayServerCommand command, List<Object> args) throws IOException {
    write(command, args);
  }

  private void handlePing() {
    serverWriter.write(RelayClientMessage.pong());
  }

  private void handleSendNatPacket(RelayServerCommand command, List<Object> args) throws IOException {
    if (p2pProxyEnabled) {
      String publicAddress = (String) args.get(0);

      proxyServer.registerPeerIfNecessary(publicAddress);

      args.set(0, proxyServer.translateToLocal(publicAddress));
    }

    writeUdp(command, args);
  }

  private void handleP2pReconnect() throws SocketException {
    proxyServer.initialize();
    p2pProxyEnabled = true;
  }

  private void handleJoinGame(RelayServerCommand command, List<Object> args) throws IOException {
    if (p2pProxyEnabled) {
      String peerAddress = (String) args.get(0);
      int peerUid = extractInt(args.get(2));

      proxyServer.registerPeerIfNecessary(peerAddress);

      args.set(0, proxyServer.translateToLocal(peerAddress));
      proxyServer.setUidForPeer(peerAddress, peerUid);
    }

    write(command, args);
  }

  private static int extractInt(Object object) {
    // JSON doesn't know integers, but double
    return ((Double) object).intValue();
  }

  private void handleConnectToPeer(RelayServerCommand command, List<Object> args) throws IOException {
    handleJoinGame(command, args);
  }

  private void handleCreateLobby(RelayServerCommand command, List<Object> args) throws IOException {
    int peerUid = extractInt(args.get(3));
    proxyServer.setUid(peerUid);

    if (p2pProxyEnabled) {
      args.set(1, ProxyUtils.translateToProxyPort(proxyServer.getPort()));
    }

    write(command, args);
  }

  private void handleConnectToProxy(RelayServerCommand command, List<Object> args) throws IOException {
    int port = extractInt(args.get(0));
    String login = (String) args.get(2);
    int uid = extractInt(args.get(3));

    InetSocketAddress socketAddress = proxyServer.bindSocket(port, uid);

    List<Object> newArgs = Arrays.asList(
        SocketAddressUtil.toString(socketAddress),
        login,
        uid
    );

    write(CONNECT_TO_PEER, newArgs);
  }

  private void handleJoinProxy(RelayServerCommand command, List<Object> args) throws IOException {
    int port = extractInt(args.get(0));
    String login = (String) args.get(2);
    int uid = extractInt(args.get(3));

    InetSocketAddress socketAddress = proxyServer.bindSocket(port, uid);

    List<Object> newArgs = Arrays.asList(
        SocketAddressUtil.toString(socketAddress),
        login,
        uid
    );

    write(JOIN_GAME, newArgs);
  }

  private void writeUdp(RelayServerCommand command, List<Object> chunks) throws IOException {
    String commandString = command.getString();

    int headerSize = commandString.length();
    String headerField = commandString.replace("\t", "/t").replace("\n", "/n");

    logger.debug("Writing data to FA, command: {}, chunks: {}", command, chunks);

    faOutputStream.writeInt(headerSize);
    faOutputStream.writeString(headerField);
    faOutputStream.writeUdpChunks(chunks);
    faOutputStream.flush();
  }

  private void write(RelayServerCommand command, List<Object> chunks) throws IOException {
    String commandString = command.getString();

    int headerSize = commandString.length();
    String headerField = commandString.replace("\t", "/t").replace("\n", "/n");

    logger.debug("Writing data to FA, command: {}, chunks: {}", command, chunks);

    faOutputStream.writeInt(headerSize);
    faOutputStream.writeString(headerField);
    faOutputStream.writeChunks(chunks);
    faOutputStream.flush();
  }

  @Override
  public void close() throws IOException {
    inputStream.close();
  }
}