package com.angrydoughnuts.android.alarmclock;

import com.angrydoughnuts.android.alarmclock.WakeLock.WakeLockException;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;


public class ReceiverAlarm extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent recvIntent) {
    Uri alarmUri = recvIntent.getData();
    long alarmId = AlarmUtil.alarmUriToId(alarmUri);

    if (AlarmUtil.isLightUri(alarmUri)) {
        Log.d("chase", "call service");
        Intent helloService = new Intent(context, HelloService.class);
        helloService.setData(alarmUri);

        context.startService(helloService);
        return;
    }

    try {
      WakeLock.acquire(context, alarmId);
    } catch (WakeLockException e) {
      if (AppSettings.isDebugMode(context)) {
        throw new IllegalStateException(e.getMessage());
      }
    }

    Intent notifyService = new Intent(context, NotificationService.class);
    notifyService.setData(alarmUri);

    context.startService(notifyService);
  }
}
