package com.faforever.client.game;

import com.faforever.client.connectivity.ConnectivityService;
import com.faforever.client.fa.ForgedAllianceService;
import com.faforever.client.map.MapService;
import com.faforever.client.patch.GameUpdateService;
import com.faforever.client.player.PlayerInfoBeanBuilder;
import com.faforever.client.player.PlayerService;
import com.faforever.client.preferences.ForgedAlliancePrefs;
import com.faforever.client.preferences.Preferences;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.relay.LocalRelayServer;
import com.faforever.client.relay.event.RehostRequestEvent;
import com.faforever.client.remote.FafService;
import com.faforever.client.remote.domain.GameInfoMessage;
import com.faforever.client.remote.domain.GameLaunchMessage;
import com.faforever.client.remote.domain.GameTypeMessage;
import com.faforever.client.remote.domain.VictoryCondition;
import com.faforever.client.replay.ReplayService;
import com.faforever.client.test.AbstractPlainJavaFxTest;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.faforever.client.fa.RatingMode.GLOBAL;
import static com.faforever.client.fa.RatingMode.RANKED_1V1;
import static com.faforever.client.game.Faction.AEON;
import static com.faforever.client.game.Faction.CYBRAN;
import static com.faforever.client.remote.domain.GameState.CLOSED;
import static com.faforever.client.remote.domain.GameState.OPEN;
import static com.faforever.client.remote.domain.GameState.PLAYING;
import static com.natpryce.hamcrest.reflection.HasAnnotationMatcher.hasAnnotation;
import static java.util.Arrays.asList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.collection.IsEmptyCollection.emptyCollectionOf;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GameServiceImplTest extends AbstractPlainJavaFxTest {

  private static final long TIMEOUT = 5000;
  private static final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;
  private static final int GAME_PORT = 1234;

  private GameServiceImpl instance;

  @Mock
  private PreferencesService preferencesService;
  @Mock
  private FafService fafService;
  @Mock
  private MapService mapService;
  @Mock
  private ForgedAllianceService forgedAllianceService;
  @Mock
  private GameUpdateService gameUpdateService;
  @Mock
  private Preferences preferences;
  @Mock
  private ForgedAlliancePrefs forgedAlliancePrefs;
  @Mock
  private ApplicationContext applicationContext;
  @Mock
  private LocalRelayServer localRelayServer;
  @Mock
  private PlayerService playerService;
  @Mock
  private ConnectivityService connectivityService;
  @Mock
  private ScheduledExecutorService scheduledExecutorService;
  @Mock
  private ReplayService replayService;
  @Mock
  private EventBus eventBus;
  @Captor
  private ArgumentCaptor<Consumer<Void>> gameLaunchedListenerCaptor;
  @Captor
  private ArgumentCaptor<ListChangeListener.Change<? extends GameInfoBean>> gameInfoBeanChangeListenerCaptor;
  @Captor
  private ArgumentCaptor<Consumer<GameTypeMessage>> gameTypeMessageListenerCaptor;
  @Captor
  private ArgumentCaptor<Consumer<GameInfoMessage>> gameInfoMessageListenerCaptor;

  @Before
  public void setUp() throws Exception {
    instance = new GameServiceImpl();
    instance.fafService = fafService;
    instance.mapService = mapService;
    instance.forgedAllianceService = forgedAllianceService;
    instance.connectivityService = connectivityService;
    instance.gameUpdateService = gameUpdateService;
    instance.preferencesService = preferencesService;
    instance.applicationContext = applicationContext;
    instance.playerService = playerService;
    instance.scheduledExecutorService = scheduledExecutorService;
    instance.localRelayServer = localRelayServer;
    instance.replayService = replayService;
    instance.eventBus = eventBus;

    when(preferencesService.getPreferences()).thenReturn(preferences);
    when(preferences.getForgedAlliance()).thenReturn(forgedAlliancePrefs);
    when(forgedAlliancePrefs.getPort()).thenReturn(GAME_PORT);
    when(connectivityService.checkConnectivity()).thenReturn(CompletableFuture.completedFuture(null));

    doAnswer(invocation -> {
      try {
        invocation.getArgumentAt(0, Runnable.class).run();
      } catch (Exception e) {
        e.printStackTrace();
      }
      return null;
    }).when(scheduledExecutorService).execute(any());

    instance.postConstruct();

    verify(fafService).addOnMessageListener(eq(GameTypeMessage.class), gameTypeMessageListenerCaptor.capture());
    verify(fafService).addOnMessageListener(eq(GameInfoMessage.class), gameInfoMessageListenerCaptor.capture());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void postConstruct() {
    verify(fafService).addOnMessageListener(eq(GameTypeMessage.class), any(Consumer.class));
    verify(fafService).addOnMessageListener(eq(GameInfoMessage.class), any(Consumer.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testJoinGameMapIsAvailable() throws Exception {
    GameInfoBean gameInfoBean = GameInfoBeanBuilder.create().defaultValues().get();

    ObservableMap<String, String> simMods = FXCollections.observableHashMap();
    simMods.put("123-456-789", "Fake mod name");

    gameInfoBean.setSimMods(simMods);
    gameInfoBean.setMapFolderName("map");

    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();
    InetSocketAddress externalSocketAddress = new InetSocketAddress(123);

    when(mapService.isInstalled("map")).thenReturn(true);
    when(connectivityService.getExternalSocketAddress()).thenReturn(externalSocketAddress);
    when(fafService.requestJoinGame(gameInfoBean.getUid(), null)).thenReturn(completedFuture(gameLaunchMessage));
    when(localRelayServer.getPort()).thenReturn(111);
    when(gameUpdateService.updateInBackground(any(), any(), any(), any())).thenReturn(completedFuture(null));

    CompletableFuture<Void> future = instance.joinGame(gameInfoBean, null).toCompletableFuture();

    assertThat(future.get(TIMEOUT, TIME_UNIT), is(nullValue()));
    verify(mapService, never()).download(any());
    verify(replayService).startReplayServer(gameInfoBean.getUid());
  }

  @Test
  public void testNoGameTypes() throws Exception {
    List<GameTypeBean> gameTypes = instance.getGameTypes();

    assertThat(gameTypes, emptyCollectionOf(GameTypeBean.class));
    assertThat(gameTypes, hasSize(0));
  }

  @Test
  public void testGameTypeIsOnlyAddedOnce() throws Exception {
    GameTypeMessage gameTypeMessage = GameTypeInfoBuilder.create().defaultValues().get();
    gameTypeMessageListenerCaptor.getValue().accept(gameTypeMessage);
    gameTypeMessageListenerCaptor.getValue().accept(gameTypeMessage);

    List<GameTypeBean> gameTypes = instance.getGameTypes();

    assertThat(gameTypes, hasSize(1));
  }

  @Test
  public void testDifferentGameTypes() throws Exception {
    GameTypeMessage gameTypeMessage1 = GameTypeInfoBuilder.create().defaultValues().get();
    GameTypeMessage gameTypeMessage2 = GameTypeInfoBuilder.create().defaultValues().get();

    gameTypeMessage1.setName("number1");
    gameTypeMessage2.setName("number2");

    gameTypeMessageListenerCaptor.getValue().accept(gameTypeMessage1);
    gameTypeMessageListenerCaptor.getValue().accept(gameTypeMessage2);

    List<GameTypeBean> gameTypes = instance.getGameTypes();

    assertThat(gameTypes, hasSize(2));
  }

  @Test
  public void testAddOnGameTypeInfoListener() throws Exception {
    @SuppressWarnings("unchecked")
    MapChangeListener<String, GameTypeBean> listener = mock(MapChangeListener.class);
    instance.addOnGameTypesChangeListener(listener);

    gameTypeMessageListenerCaptor.getValue().accept(GameTypeInfoBuilder.create().defaultValues().get());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAddOnGameStartedListener() throws Exception {
    Process process = mock(Process.class);
    int gpgPort = 111;

    NewGameInfo newGameInfo = NewGameInfoBuilder.create().defaultValues().get();
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();
    gameLaunchMessage.setArgs(asList("/foo bar", "/bar foo"));
    InetSocketAddress externalSocketAddress = new InetSocketAddress(123);

    when(localRelayServer.getPort()).thenReturn(gpgPort);
    when(forgedAllianceService.startGame(
        gameLaunchMessage.getUid(), gameLaunchMessage.getMod(), null, asList("/foo", "bar", "/bar", "foo"), GLOBAL, gpgPort, false)
    ).thenReturn(process);
    when(connectivityService.getExternalSocketAddress()).thenReturn(externalSocketAddress);
    when(gameUpdateService.updateInBackground(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(newGameInfo)).thenReturn(completedFuture(gameLaunchMessage));

    CountDownLatch gameStartedLatch = new CountDownLatch(1);
    CountDownLatch gameTerminatedLatch = new CountDownLatch(1);
    instance.gameRunningProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue) {
        gameStartedLatch.countDown();
      } else {
        gameTerminatedLatch.countDown();
      }
    });

    CountDownLatch processLatch = new CountDownLatch(1);

    process = mock(Process.class);
    doAnswer(invocation -> {
      processLatch.await();
      return null;
    }).when(process).waitFor();

    instance.hostGame(newGameInfo).toCompletableFuture().get(TIMEOUT, TIME_UNIT);
    gameStartedLatch.await(TIMEOUT, TIME_UNIT);
    processLatch.countDown();

    gameTerminatedLatch.await(TIMEOUT, TIME_UNIT);
    verify(forgedAllianceService).startGame(
        gameLaunchMessage.getUid(), gameLaunchMessage.getMod(), null, asList("/foo", "bar", "/bar", "foo"), GLOBAL,
        gpgPort, false);
    verify(replayService).startReplayServer(gameLaunchMessage.getUid());
  }

  @Test
  public void testWaitForProcessTerminationInBackground() throws Exception {
    instance.gameRunning.set(true);

    CompletableFuture<Void> disconnectedFuture = new CompletableFuture<>();

    instance.gameRunningProperty().addListener((observable, oldValue, newValue) -> {
      if (!newValue) {
        disconnectedFuture.complete(null);
      }
    });

    Process process = mock(Process.class);

    instance.spawnTerminationListener(process);

    disconnectedFuture.get(5000, TimeUnit.MILLISECONDS);

    verify(process).waitFor();
  }

  @Test
  public void testOnGames() throws Exception {
    assertThat(instance.getGameInfoBeans(), empty());

    GameInfoMessage multiGameInfoMessage = new GameInfoMessage();
    multiGameInfoMessage.setGames(asList(
        GameInfoMessageBuilder.create(1).defaultValues().get(),
        GameInfoMessageBuilder.create(2).defaultValues().get()
    ));

    gameInfoMessageListenerCaptor.getValue().accept(multiGameInfoMessage);

    assertThat(instance.getGameInfoBeans(), hasSize(2));
  }

  @Test
  public void testOnGameInfoAdd() {
    assertThat(instance.getGameInfoBeans(), empty());

    GameInfoMessage gameInfoMessage1 = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage1);

    GameInfoMessage gameInfoMessage2 = GameInfoMessageBuilder.create(2).defaultValues().title("Game 2").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage2);

    GameInfoBean gameInfoBean1 = new GameInfoBean(gameInfoMessage1);
    GameInfoBean gameInfoBean2 = new GameInfoBean(gameInfoMessage2);

    assertThat(instance.getGameInfoBeans(), containsInAnyOrder(gameInfoBean1, gameInfoBean2));
  }

  @Test
  public void testOnGameInfoMessageSetsCurrentGameIfUserIsInAndStatusOpen() throws Exception {
    assertThat(instance.getCurrentGame(), nullValue());

    when(playerService.getCurrentPlayer()).thenReturn(PlayerInfoBeanBuilder.create("PlayerName").get());

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1234).defaultValues()
        .state(OPEN)
        .addTeamMember("1", "PlayerName").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    assertThat(instance.getCurrentGame(), notNullValue());
    assertThat(instance.getCurrentGame().getUid(), is(1234));
  }

  @Test
  public void testOnGameInfoMessageDoesntSetCurrentGameIfUserIsInAndStatusNotOpen() throws Exception {
    assertThat(instance.getCurrentGame(), nullValue());

    when(playerService.getCurrentPlayer()).thenReturn(PlayerInfoBeanBuilder.create("PlayerName").get());

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1234).defaultValues()
        .state(PLAYING)
        .addTeamMember("1", "PlayerName").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    assertThat(instance.getCurrentGame(), nullValue());
  }

  @Test
  public void testOnGameInfoMessageDoesntSetCurrentGameIfUserDoesntMatch() throws Exception {
    assertThat(instance.getCurrentGame(), nullValue());

    when(playerService.getCurrentPlayer()).thenReturn(PlayerInfoBeanBuilder.create("PlayerName").get());

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1234).defaultValues().addTeamMember("1", "Other").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    assertThat(instance.getCurrentGame(), nullValue());
  }

  @Test
  public void testOnGameInfoModify() throws InterruptedException {
    assertThat(instance.getGameInfoBeans(), empty());

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1").state(PLAYING).get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    CountDownLatch changeLatch = new CountDownLatch(1);
    GameInfoBean gameInfoBean = instance.getGameInfoBeans().iterator().next();
    gameInfoBean.titleProperty().addListener((observable, oldValue, newValue) -> {
      changeLatch.countDown();
    });

    gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1 modified").state(PLAYING).get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    changeLatch.await();
    assertEquals(gameInfoMessage.getTitle(), gameInfoBean.getTitle());
  }

  @Test
  public void testOnGameInfoRemove() {
    assertThat(instance.getGameInfoBeans(), empty());

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues().title("Game 1").get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    gameInfoMessage = GameInfoMessageBuilder.create(1).title("Game 1").defaultValues().state(CLOSED).get();
    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    assertThat(instance.getGameInfoBeans(), empty());
  }

  @Test
  public void testStartSearchRanked1v1() throws Exception {
    GameLaunchMessage gameLaunchMessage = new GameLaunchMessage();
    gameLaunchMessage.setMod("ladder1v1");
    gameLaunchMessage.setUid(123);
    gameLaunchMessage.setArgs(Collections.emptyList());
    gameLaunchMessage.setMapname("scmp_037");
    when(fafService.startSearchRanked1v1(CYBRAN, GAME_PORT)).thenReturn(CompletableFuture.completedFuture(gameLaunchMessage));
    when(gameUpdateService.updateInBackground(GameType.LADDER_1V1.getString(), null, Collections.emptyMap(), Collections.emptySet())).thenReturn(CompletableFuture.completedFuture(null));
    when(scheduledExecutorService.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenReturn(mock(ScheduledFuture.class));
    when(localRelayServer.getPort()).thenReturn(111);
    when(mapService.isInstalled("scmp_037")).thenReturn(false);
    when(mapService.download("scmp_037")).thenReturn(CompletableFuture.completedFuture(null));

    CompletableFuture<Void> future = instance.startSearchRanked1v1(CYBRAN).toCompletableFuture();

    verify(fafService).startSearchRanked1v1(CYBRAN, GAME_PORT);
    verify(mapService).download("scmp_037");
    verify(replayService).startReplayServer(123);
    verify(forgedAllianceService, timeout(100)).startGame(eq(123), eq("ladder1v1"), eq(CYBRAN), eq(asList("/team", "1", "/players", "2")), eq(RANKED_1V1), anyInt(), eq(false));
    assertThat(future.get(TIMEOUT, TIME_UNIT), is(nullValue()));
  }

  @Test
  public void testStartSearchRanked1v1GameRunningDoesNothing() throws Exception {
    Process process = mock(Process.class);
    when(process.isAlive()).thenReturn(true);

    NewGameInfo newGameInfo = NewGameInfoBuilder.create().defaultValues().get();
    GameLaunchMessage gameLaunchMessage = GameLaunchMessageBuilder.create().defaultValues().get();
    InetSocketAddress externalSocketAddress = new InetSocketAddress(123);

    when(connectivityService.getExternalSocketAddress()).thenReturn(externalSocketAddress);
    when(forgedAllianceService.startGame(anyInt(), any(), any(), any(), any(), anyInt(), eq(false))).thenReturn(process);
    when(gameUpdateService.updateInBackground(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(newGameInfo)).thenReturn(completedFuture(gameLaunchMessage));
    when(localRelayServer.getPort()).thenReturn(111);

    CountDownLatch gameRunningLatch = new CountDownLatch(1);
    instance.gameRunningProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue) {
        gameRunningLatch.countDown();
      }
    });

    instance.hostGame(newGameInfo);
    gameRunningLatch.await(TIMEOUT, TIME_UNIT);

    instance.startSearchRanked1v1(AEON);

    assertThat(instance.searching1v1Property().get(), is(false));
  }

  @Test
  public void testStopSearchRanked1v1() throws Exception {
    instance.searching1v1Property().set(true);
    instance.stopSearchRanked1v1();
    assertThat(instance.searching1v1Property().get(), is(false));
    verify(fafService).stopSearchingRanked();
  }

  @Test
  public void testStopSearchRanked1v1NotSearching() throws Exception {
    instance.searching1v1Property().set(false);
    instance.stopSearchRanked1v1();
    assertThat(instance.searching1v1Property().get(), is(false));
    verify(fafService, never()).stopSearchingRanked();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAddOnGameInfoBeanListener() throws Exception {
    ListChangeListener<GameInfoBean> listener = mock(ListChangeListener.class);
    instance.addOnGameInfoBeansChangeListener(listener);

    GameInfoMessage gameInfoMessage = GameInfoMessageBuilder.create(1).defaultValues()
        .host("host")
        .title("title")
        .mapName("mapName")
        .featuredMod("mod")
        .numPlayers(2)
        .maxPlayers(4)
        .gameType(VictoryCondition.DOMINATION)
        .state(PLAYING)
        .passwordProtected(false)
        .get();

    gameInfoMessageListenerCaptor.getValue().accept(gameInfoMessage);

    verify(listener).onChanged(gameInfoBeanChangeListenerCaptor.capture());

    ListChangeListener.Change<? extends GameInfoBean> change = gameInfoBeanChangeListenerCaptor.getValue();
    assertThat(change.next(), is(true));
    List<? extends GameInfoBean> addedSubList = change.getAddedSubList();
    assertThat(addedSubList, hasSize(1));

    GameInfoBean gameInfoBean = addedSubList.get(0);
    assertThat(gameInfoBean.getUid(), is(1));
    assertThat(gameInfoBean.getHost(), is("host"));
    assertThat(gameInfoBean.getTitle(), is("title"));
    assertThat(gameInfoBean.getNumPlayers(), is(2));
    assertThat(gameInfoBean.getMaxPlayers(), is(4));
    assertThat(gameInfoBean.getFeaturedMod(), is("mod"));
    assertThat(gameInfoBean.getVictoryCondition(), is(VictoryCondition.DOMINATION));
    assertThat(gameInfoBean.getStatus(), is(PLAYING));
  }

  @Test
  public void testSubscribeEventBus() throws Exception {
    verify(eventBus).register(instance);

    assertThat(ReflectionUtils.findMethod(
        instance.getClass(), "onRehostRequest", RehostRequestEvent.class),
        hasAnnotation(Subscribe.class));
  }

  @Test
  public void testRehostIfGameIsNotRunning() throws Exception {
    GameInfoBean gameInfoBean = GameInfoBeanBuilder.create().defaultValues().get();
    instance.currentGame.set(gameInfoBean);

    when(gameUpdateService.updateInBackground(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(any())).thenReturn(completedFuture(GameLaunchMessageBuilder.create().defaultValues().get()));

    instance.onRehostRequest(new RehostRequestEvent());

    verify(forgedAllianceService).startGame(anyInt(), eq("faf"), eq(null), anyListOf(String.class), eq(GLOBAL), anyInt(), eq(true));
  }

  @Test
  public void testRehostIfGameIsRunning() throws Exception {
    instance.gameRunning.set(true);

    GameInfoBean gameInfoBean = GameInfoBeanBuilder.create().defaultValues().get();
    instance.currentGame.set(gameInfoBean);

    when(gameUpdateService.updateInBackground(any(), any(), any(), any())).thenReturn(completedFuture(null));
    when(fafService.requestHostGame(any())).thenReturn(completedFuture(GameLaunchMessageBuilder.create().defaultValues().get()));

    instance.onRehostRequest(new RehostRequestEvent());

    verify(forgedAllianceService, never()).startGame(anyInt(), any(), any(), any(), any(), anyInt(), anyBoolean());
  }
}
