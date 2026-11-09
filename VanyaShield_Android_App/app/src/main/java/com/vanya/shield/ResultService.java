package com.vanya.shield;
import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
@SuppressWarnings("deprecation")
public class ResultService extends IntentService {
 public ResultService(){super("VanyaShieldResults");}
 @Override protected void onHandleIntent(Intent intent){
  if(intent==null)return;
  Bundle b=intent.getBundleExtra("result");
  String out=b==null?"Termux не вернул результат. Проверь разрешение RUN_COMMAND.":"Nmap, код: "+b.getInt("exitCode",-1)+"\n"+b.getString("stdout","")+"\n"+b.getString("stderr","");
  if(out.length()>16000)out=out.substring(0,16000);
  getSharedPreferences("MainActivity",MODE_PRIVATE).edit().putString("scan",out).apply();
 }
}