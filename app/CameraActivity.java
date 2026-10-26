package local.codex.portalprotectcamera;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.content.Intent;
import android.widget.TextView;
import android.view.WindowManager;

public class CameraActivity extends Activity {
  public void onCreate(Bundle state) {
    super.onCreate(state);
    android.view.WindowManager.LayoutParams p=getWindow().getAttributes();
    p.width=1;p.height=1;p.gravity=android.view.Gravity.TOP|android.view.Gravity.LEFT;
    p.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    p.dimAmount=0;getWindow().setAttributes(p);
    getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    apply(getIntent());
  }
  protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);apply(intent);}
  void apply(Intent intent){
    // Persist transport selection independently of source capture. The legacy
    // "nuc" spelling simply disables native Protect; it keeps RTSP available.
    String mode=intent.getStringExtra("protect_mode");
    if(mode!=null&&(mode.equals("portal")||mode.equals("nuc")))getSharedPreferences("camera",0).edit().putBoolean("protect_native",mode.equals("portal")).commit();
    boolean stop=intent.getBooleanExtra("stop",false);
    getSharedPreferences("camera",0).edit().putBoolean("enabled",!stop).apply();
    if(stop)stopService(new Intent(this,CameraService.class));
    else startForegroundService(new Intent(this,CameraService.class).putExtra("protect_reconnect",intent.getBooleanExtra("protect_reconnect",false)));
    setContentView(new android.view.View(this));
    if(stop)finish();
  }
}
