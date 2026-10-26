package local.codex.portalprotectcamera;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context context,Intent intent) {
    // A deliberately stopped source must remain stopped across a reboot.
    if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())&&context.getSharedPreferences("camera",0).getBoolean("enabled",true))
      context.startActivity(new Intent(context,CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION));
  }
}
