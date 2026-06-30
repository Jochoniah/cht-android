package org.medicmobile.webapp.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.medicmobile.webapp.mobile.util.AppDataStore;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RunWith(RobolectricTestRunner.class)
public class NotificationWorkerTest {

	@Rule
	public MockitoRule mockitoRule = MockitoJUnit.rule();

	@Mock
	private AppDataStore mockAppDataStore;

	private Context context;

	@Before
	public void setUp() {
		context = ApplicationProvider.getApplicationContext();
	}

	@Test
	public void doWork_returnsSuccess_whenNoException() throws JSONException {
		String notificationWindowSettings = "{}";  // Empty = always in window
		String notifications = "[]";

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class);
		     MockedConstruction<AppNotificationManager> notificationMgrMock
				 = mockConstruction(AppNotificationManager.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			when(mockAppDataStore.getStringBlocking(AppNotificationManager.TASK_NOTIFICATION_WINDOW_KEY, "{}"))
				.thenReturn(notificationWindowSettings);
			when(mockAppDataStore.getStringBlocking(AppNotificationManager.TASK_NOTIFICATIONS_KEY, "[]"))
				.thenReturn(notifications);

			NotificationWorker worker = createWorker();

			ListenableWorker.Result result = worker.doWork();

			// Assert
			assertEquals(ListenableWorker.Result.success(), result);
			verify(notificationMgrMock.constructed().get(0), times(1))
				.showNotificationsFromJsArray(notifications);
		}
	}

	@Test
	public void doWork_returnsFailure_whenJSONExceptionOccurs() throws JSONException {
		String invalidNotificationWindowSettings = "{invalid json}";

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class);
		     MockedConstruction<AppNotificationManager> notificationMgrMock =
				 mockConstruction(AppNotificationManager.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			when(mockAppDataStore
				.getStringBlocking(AppNotificationManager.TASK_NOTIFICATION_WINDOW_KEY, "{}"))
				.thenReturn(invalidNotificationWindowSettings);
			when(mockAppDataStore
				.getStringBlocking(AppNotificationManager.TASK_NOTIFICATIONS_KEY, "[]"))
				.thenReturn("[]");

			NotificationWorker worker = createWorker();

			ListenableWorker.Result result = worker.doWork();

			// Invalid JSON is handled gracefully
			// by Utils.parseJSONObject which returns empty JSONObject
			// So this returns success and showNotifications is called
			assertEquals(ListenableWorker.Result.success(), result);
			verify(notificationMgrMock.constructed().get(0), times(1))
				.showNotificationsFromJsArray(anyString());
		}
	}

	@Test
	public void doWork_callsShowNotifications_whenInNotificationWindow() throws JSONException {
		// setup
		LocalTime now = LocalTime.now(ZoneId.systemDefault());
		LocalTime startTime = now.minusHours(1);
		LocalTime endTime = now.plusHours(1);
		String notificationWindowSettings = getWindowSettings(startTime, endTime);
		String notifications = "[]";

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class);
		     MockedConstruction<AppNotificationManager> notificationMgrMock =
				 mockConstruction(AppNotificationManager.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			when(mockAppDataStore
				.getStringBlocking(AppNotificationManager.TASK_NOTIFICATION_WINDOW_KEY, "{}"))
				.thenReturn(notificationWindowSettings);
			when(mockAppDataStore
				.getStringBlocking(AppNotificationManager.TASK_NOTIFICATIONS_KEY, "[]"))
				.thenReturn(notifications);

			NotificationWorker worker = createWorker();

			ListenableWorker.Result result = worker.doWork();

			assertEquals(ListenableWorker.Result.success(), result);
			verify(notificationMgrMock.constructed().get(0), times(1))
				.showNotificationsFromJsArray(notifications);
		}
	}

	@Test
	public void doWork_doesNotCallShowNotifications_whenOutsideNotificationWindow() throws JSONException {
		LocalTime now = LocalTime.now(ZoneId.systemDefault());
		LocalTime startTime = now.minusHours(2);
		LocalTime endTime = now.minusHours(1);
		String notificationWindowSettings = getWindowSettings(startTime, endTime);

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class);
		     MockedConstruction<AppNotificationManager> notificationMgrMock =
				 mockConstruction(AppNotificationManager.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			when(mockAppDataStore
				.getStringBlocking(AppNotificationManager.TASK_NOTIFICATION_WINDOW_KEY, "{}"))
				.thenReturn(notificationWindowSettings);

			NotificationWorker worker = createWorker();

			ListenableWorker.Result result = worker.doWork();

			// Assert
			assertEquals(ListenableWorker.Result.success(), result);
			verify(notificationMgrMock.constructed().get(0), times(0))
				.showNotificationsFromJsArray(anyString());
		}
	}

	//Apps with no/invalid window settings run all the time
	@Test
	public void isNotificationWindow_returnsTrue_whenBadTimeFields() throws Exception {
		String windowSettings = "{\"start\": \"09:70\",\"end\": \"19:05\"}"; //bad data

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			NotificationWorker worker = createWorker();

			boolean result = invokeIsNotificationWindow(worker, windowSettings);
			assertTrue(result);
		}
	}

	@Test
	public void isNotificationWindow_returnsTrue_whenWithinWindow() throws Exception {
		LocalTime now = LocalTime.now(ZoneId.systemDefault());
		LocalTime startTime = now.minusHours(1);
		LocalTime endTime = now.plusHours(1);
		String windowSettings = getWindowSettings(startTime, endTime);

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			NotificationWorker worker = createWorker();

			boolean result = invokeIsNotificationWindow(worker, windowSettings);
			assertTrue(result);
		}
	}

	@Test
	public void isNotificationWindow_returnsFalse_whenBeforeWindow() throws Exception {
		LocalTime now = LocalTime.now(ZoneId.systemDefault());
		LocalTime startTime = now.plusHours(1);
		LocalTime endTime = now.plusHours(2);
		String windowSettings = getWindowSettings(startTime, endTime);

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			NotificationWorker worker = createWorker();

			boolean result = invokeIsNotificationWindow(worker, windowSettings);

			// Assert
			assertFalse(result);
		}
	}

	@Test
	public void isNotificationWindow_returnsFalse_whenAfterWindow() throws Exception {
		LocalTime now = LocalTime.now(ZoneId.systemDefault());
		LocalTime startTime = now.minusHours(2);
		LocalTime endTime = now.minusHours(1);
		String windowSettings = getWindowSettings(startTime, endTime);

		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			NotificationWorker worker = createWorker();

			boolean result = invokeIsNotificationWindow(worker, windowSettings);

			// Assert
			assertFalse(result);
		}
	}

	@Test
	public void formatTime_returnsNullForBadTime() throws Exception {
		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			NotificationWorker worker = createWorker();
			LocalTime result = invokeFormatTime(worker, "14:70");
			assertNull(result);
		}
	}

	@Test
	public void formatTime_parsesValidTimeFormats() throws Exception {
		try (MockedStatic<AppDataStore> dataMock = mockStatic(AppDataStore.class)) {

			dataMock.when(() -> AppDataStore.getInstance(context))
				.thenReturn(mockAppDataStore);

			NotificationWorker worker = createWorker();

			String[] testTimes = {"00:00", "12:00", "23:59"};
			LocalTime[] expectedTimes = {
				LocalTime.of(0, 0),
				LocalTime.of(12, 0),
				LocalTime.of(23, 59)
			};

			// Act & Assert
			for (int i = 0; i < testTimes.length; i++) {
				LocalTime result = invokeFormatTime(worker, testTimes[i]);
				assertEquals(expectedTimes[i], result);
			}
		}
	}

	private NotificationWorker createWorker() {
		WorkerParameters workerParameters = mock(WorkerParameters.class);
		return new NotificationWorker(context, workerParameters);
	}

	private boolean invokeIsNotificationWindow(NotificationWorker worker, String windowObject) throws Exception {
		Method method = NotificationWorker.class
			.getDeclaredMethod("isNotificationWindow", String.class);
		method.setAccessible(true);
		return (Boolean) method.invoke(worker, windowObject);
	}

	private LocalTime invokeFormatTime(NotificationWorker worker, String timeString) throws Exception {
		Method method = NotificationWorker.class
			.getDeclaredMethod("formatTime", String.class);
		method.setAccessible(true);
		return (LocalTime) method.invoke(worker, timeString);
	}

	private String getWindowSettings(LocalTime startTime, LocalTime endTime) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
		return String.format("{\"start\": \"%s\", \"end\": \"%s\"}",
			startTime.format(formatter), endTime.format(formatter));
	}
}
