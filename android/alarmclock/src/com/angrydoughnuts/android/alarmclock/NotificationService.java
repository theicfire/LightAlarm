/****************************************************************************
 * Copyright 2010 kraigs.android@gmail.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 ****************************************************************************/

package com.angrydoughnuts.android.alarmclock;

import java.util.LinkedList;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;

public class NotificationService extends Service {
  public class NoAlarmsException extends Exception {
    private static final long serialVersionUID = 1L;
  }

  private final int ALERT_ID = 42;

  // Data
  private LinkedList<Long> firingAlarms;
  private AlarmClockServiceBinder service;
  private DbAccessor db;
  // Notification tools
  private MediaPlayer mediaPlayer;
  private Ringtone fallbackSound;
  private Vibrator vibrator;
  private Handler handler;
  private VolumeIncreaser volumeIncreaseCallback; 
  private Runnable soundCheck;

  @Override
  public IBinder onBind(Intent intent) {
    return new NotificationServiceInterfaceStub(this);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    firingAlarms = new LinkedList<Long>();
    // Access to in-memory and persistent data structures.
    service = new AlarmClockServiceBinder(getApplicationContext());
    service.bind();
    db = new DbAccessor(getApplicationContext());

    // Setup audio.
    final AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    // Force the alarm stream to be maximum volume.  This will allow the user
    // to select a volume between 0 and 100 percent via the settings activity.
    audio.setStreamVolume(AudioManager.STREAM_ALARM,
        audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
    // Setup the media play.
    mediaPlayer = new MediaPlayer();
    // Make it use the previously configured alarm stream.
    mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
    // The media player can fail for lots of reasons.  Try to setup a backup
    // sound for use when the media player fails.
    fallbackSound = RingtoneManager.getRingtone(getApplicationContext(),
        AlarmUtil.getDefaultAlarmUri());
    if (fallbackSound == null) {
      Uri superFallback = RingtoneManager.getValidRingtoneUri(getApplicationContext());
      fallbackSound = RingtoneManager.getRingtone(getApplicationContext(), superFallback);
    }
    // Make the fallback sound use the alarm stream as well.
    if (fallbackSound != null) {
      fallbackSound.setStreamType(AudioManager.STREAM_ALARM);
    }

    // Instantiate a vibrator.  That's fun to say.
    vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

    // Setup a self-scheduling event loops.
    handler = new Handler();
    volumeIncreaseCallback = new VolumeIncreaser();
    soundCheck = new Runnable() {
      @Override
      public void run() {
        // Some sound should always be playing.
        if (!mediaPlayer.isPlaying() &&
            fallbackSound != null && !fallbackSound.isPlaying()) { 
          fallbackSound.play();
        }

        long next = AlarmUtil.millisTillNextInterval(AlarmUtil.Interval.SECOND);
        handler.postDelayed(soundCheck, next);
      }
    };
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    final NotificationManager manager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    manager.cancel(ALERT_ID);
    db.closeConnections();
    service.unbind();
    mediaPlayer.release();
    if (AppSettings.isDebugMode(getApplicationContext())) {
      if (firingAlarms.size() != 0) {
        throw new IllegalStateException("Notification service terminated with pending notifications.");
      }
      WakeLock.assertNoneHeld();
    }
  }

  // OnStart was depreciated in SDK 5.  It is here for backwards compatibility.
  // http://android-developers.blogspot.com/2010/02/service-api-changes-starting-with.html
  @Override
  public void onStart(Intent intent, int startId) {
    handleStart(intent, startId);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleStart(intent, startId);
    return START_NOT_STICKY;
  }

  private void handleStart(Intent intent, int startId) {
    // startService called from alarm receiver with an alarm id url.
    if (intent != null && intent.getData() != null) {
      long alarmId = AlarmUtil.alarmUriToId(intent.getData());
      if (AppSettings.isDebugMode(getApplicationContext())) {
        WakeLock.assertHeld(alarmId);
      }
      Intent notifyActivity = new Intent(getApplicationContext(), ActivityAlarmNotification.class);
      notifyActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(notifyActivity);

      boolean firstAlarm = firingAlarms.size() == 0;
      if (!firingAlarms.contains(alarmId)) {
        firingAlarms.add(alarmId);
      }

      if (firstAlarm) {
        soundAlarm(alarmId);
      }
    }
  }

  public long currentAlarmId() throws NoAlarmsException {
    if (firingAlarms.size() == 0) {
      throw new NoAlarmsException();
    }
    return firingAlarms.getFirst();
  }

  public int firingAlarmCount() {
    return firingAlarms.size();
  }

  public float volume() {
    return volumeIncreaseCallback.volume();
  }

  public void acknowledgeCurrentNotification(int snoozeMinutes) throws NoAlarmsException {
    long alarmId = currentAlarmId();
    if (firingAlarms.contains(alarmId)) {
      firingAlarms.remove(alarmId);
      if (snoozeMinutes <= 0) {
        service.acknowledgeAlarm(alarmId);
      } else {
        service.snoozeAlarmFor(alarmId, snoozeMinutes);
      }
    }
    stopNotifying();

    // If this was the only alarm firing, stop the service.  Otherwise,
    // start the next alarm in the stack.
    if (firingAlarms.size() == 0) {
      stopSelf();
    } else {
      soundAlarm(alarmId);
    }
    WakeLock.release(alarmId);
  }

  private void soundAlarm(long alarmId) {
    // Update the notification bar.
    Intent notificationActivity = new Intent(getApplicationContext(), ActivityAlarmClock.class);
    PendingIntent launch = PendingIntent.getActivity(getApplicationContext(), 0, notificationActivity, 0);

    // TODO cleanup this notification.  maybe make it blink?
    String text = "FIRING ALARMS:";
    for (Long id : firingAlarms) {
      text += " " + id;
    }
    final NotificationManager manager =
      (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    Notification notification = new Notification(R.drawable.alarmclock_notification, null, 0);
    notification.flags |= Notification.FLAG_ONGOING_EVENT;
    notification.setLatestEventInfo(getApplicationContext(), text, null, launch);
    manager.notify(ALERT_ID, notification);    

    // Begin notifying based on settings for this alaram.
    AlarmSettings settings = db.readAlarmSettings(alarmId);
    if (settings.getVibrate()) {
      vibrator.vibrate(new long[] {500, 500}, 0);
    }

    volumeIncreaseCallback.reset(settings);
    mediaPlayer.reset();
    mediaPlayer.setLooping(true);
    try {
      mediaPlayer.setDataSource(getApplicationContext(), settings.getTone());
      mediaPlayer.prepare();
      mediaPlayer.start();
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Start periodic events for handling this notification.
    handler.post(volumeIncreaseCallback);
    handler.post(soundCheck);
  }

  private void stopNotifying() {
    // Stop periodic events.
    handler.removeCallbacks(volumeIncreaseCallback);
    handler.removeCallbacks(soundCheck);

    // Stop notifying.
    vibrator.cancel();
    mediaPlayer.stop();
    if (fallbackSound != null) {
      fallbackSound.stop();
    }
  }

  /**
   * Helper class for gradually increasing the volume of the alarm audio
   * stream.
   */
  private final class VolumeIncreaser implements Runnable {
    float start;
    float end;
    float increment;

    public float volume() {
      return start;
    }

    public void reset(AlarmSettings settings) {
      start = (float) (settings.getVolumeStartPercent() / 100.0);
      end = (float) (settings.getVolumeEndPercent() / 100.0);
      increment = (end - start) / (float) settings.getVolumeChangeTimeSec();
      mediaPlayer.setVolume(start, start);
    }

    @Override
    public void run() {
      start += increment;
      if (start > end) {
        start = end;
      }
      mediaPlayer.setVolume(start, start);

      if (Math.abs(start - end) > (float) 0.0001) {
        handler.postDelayed(volumeIncreaseCallback, 1000);
      }
    }
  }
}