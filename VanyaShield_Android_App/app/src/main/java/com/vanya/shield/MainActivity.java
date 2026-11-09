package com.vanya.shield;

import android.app.Activity;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private final int bg=0xff0d1625, card=0xff18283e, aqua=0xff51d8c0, white=0xffedf5ff;
    private LinearLayout page;
    private EditText target;
    private CheckBox consent;
    private TextView results;
    private int tab=0;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        show(0);
    }
    private GradientDrawable shape(int color) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(22); return d;
    }
    private TextView txt(String value,int size,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextColor(color); v.setTextSize(size);
        v.setPadding(14,12,14,12); return v;
    }
    private LinearLayout group() {
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(14,14,14,14);
        l.setBackground(shape(card));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,14);
        page.addView(l,p);return l;
    }
    private void title(LinearLayout parent,String text) {
        TextView t=txt(text,19,white);t.setTypeface(null,Typeface.BOLD);parent.addView(t);
    }
    private void line(LinearLayout parent,String text) {parent.addView(txt(text,14,0xffb3c6da));}
    private void button(LinearLayout parent,String label,Runnable task) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(bg);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(aqua));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,5,0,5);
        parent.addView(b,p);b.setOnClickListener(v->task.run());
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void show(int which) {
        tab=which;LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);
        layout.setBackgroundColor(bg);setContentView(layout);
        TextView head=txt("VANYA / SHIELD",24,aqua);head.setTypeface(null,Typeface.BOLD);layout.addView(head);
        layout.addView(txt("Локальный центр сетевой защиты · без телеметрии",12,0xffb3c6da));
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(0);layout.addView(nav);
        String[] tabs={"Обзор","Сеть","Трафик","Защита"};
        for(int i=0;i<tabs.length;i++){
            final int v=i;TextView t=txt(tabs[i],12,i==which?aqua:white);
            t.setGravity(Gravity.CENTER);
            nav.addView(t,new LinearLayout.LayoutParams(0,-2,1));
            t.setOnClickListener(w->show(v));
        }
        ScrollView scroll=new ScrollView(this);layout.addView(scroll);
        page=new LinearLayout(this);page.setOrientation(1);page.setPadding(14,14,14,28);scroll.addView(page);
        switch(which){
            case 0: overview(); break;
            case 1: network(); break;
            case 2: traffic(); break;
            default: privacy();break;
        }
    }
    private void overview() {
        LinearLayout g=group();title(g,"Защита под твоим контролем");
        line(g,"Приложение не имеет доступа в Интернет, не запускает скрытый захват и не меняет системные настройки.");
        line(g,"Инструменты Nmap, PCAPdroid, SharkDroid и Rethink устанавливаются отдельно. Это единая панель управления, а не их копия.");
        button(g,"Перейти к проверке сети",()->show(1));
        g=group();title(g,"Памятка");
        line(g,"Анализируй только свои устройства или сети с разрешения владельца. PCAP-файлы могут содержать личные данные.");
    }
    private boolean localAddress(String ip) {
        if(!ip.matches("(\\d{1,3}\\.){3}\\d{1,3}"))return false;
        String[] s=ip.split("\\.");int[] a=new int[4];
        try{for(int i=0;i<4;i++){a[i]=Integer.parseInt(s[i]);if(a[i]>255)return false;}}
        catch(Exception e){return false;}
        return a[0]==10 || a[0]==192 && a[1]==168 || a[0]==172 && a[1]>=16 && a[1]<=31;
    }
    private void network() {
        LinearLayout g=group();title(g,"Nmap · один локальный хост");
        line(g,"Быстрая TCP-проверка портов. Никакого сканирования всего Интернета.");
        target=new EditText(this);target.setTextColor(white);target.setHintTextColor(0xffa0b4cb);
        target.setHint("192.168.1.1");target.setSingleLine(true);
        target.setInputType(android.text.InputType.TYPE_CLASS_TEXT);g.addView(target);
        consent=new CheckBox(this);consent.setText("Это мой хост или есть разрешение");
        consent.setTextColor(white);g.addView(consent);
        button(g,"Проверить порты",()->{
            String ip=target.getText().toString().trim();
            if(!consent.isChecked()){toast("Подтверди разрешение");return;}
            if(!localAddress(ip)){toast("Нужен один частный IPv4-адрес");return;}
            runNmap(ip);
        });
        button(g,"Открыть Termux",()->open("com.termux"));
        g=group();title(g,"Результат");
        String saved=getPreferences(MODE_PRIVATE).getString("scan","Проверка ещё не запускалась.");
        results=txt(saved,13,white);g.addView(results);
        line(g,"Настройка: в Termux установи pkg install nmap; разреши RUN_COMMAND и allow-external-apps=true.");
    }
    private void runNmap(String ip) {
        Intent cmd=new Intent("com.termux.RUN_COMMAND");
        cmd.setClassName("com.termux","com.termux.app.RunCommandService");
        cmd.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/nmap");
        cmd.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
            new String[]{"-sT","-Pn","-F","--max-retries","1","--host-timeout","25s",ip});
        cmd.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);
        cmd.putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home");
        PendingIntent done=PendingIntent.getService(this,1,
            new Intent(this,ResultService.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        cmd.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",done);
        try{startService(cmd);results.setText("Проверка запущена. Вернись на эту вкладку через несколько секунд.");}
        catch(Exception e){toast("Не удалось запустить Nmap: "+e.getClass().getSimpleName());}
    }
    private void traffic() {
        LinearLayout g=group();title(g,"PCAPdroid · трафик приложений");
        line(g,"Отдельное приложение для захвата соединений. Запуск только с твоим подтверждением.");
        button(g,"Начать захват",()->pcap("start"));
        button(g,"Остановить захват",()->pcap("stop"));
        button(g,"Открыть PCAPdroid",()->open("com.emanuelef.remote_capture"));
        g=group();title(g,"SharkDroid · Wireshark");
        line(g,"Анализ пакетов выполняется в SharkDroid; VanyaShield его не перехватывает и не сохраняет.");
        button(g,"Открыть SharkDroid",()->open("org.sharkdroid"));
    }
    private void pcap(String op) {
        Intent i=new Intent(Intent.ACTION_VIEW);
        i.setClassName("com.emanuelef.remote_capture","com.emanuelef.remote_capture.activities.CaptureCtrl");
        i.putExtra("action",op);
        if("start".equals(op)){i.putExtra("root_capture",true);i.putExtra("pcap_dump_mode","none");}
        try{startActivityForResult(i,601);}catch(Exception e){toast("Установи PCAPdroid и разреши управление");}
    }
    private void privacy() {
        LinearLayout g=group();title(g,"Rethink DNS · Firewall");
        line(g,"Отдельный firewall позволяет ограничивать соединения приложений и DNS-запросы.");
        line(g,"Если у тебя работает WireGuard, не включай второй Android VPN одновременно.");
        button(g,"Открыть Rethink DNS",()->open("com.celzero.bravedns"));
        g=group();title(g,"Безопасность");
        line(g,"VanyaShield не отключает SELinux, не меняет ядро, не сканирует сеть без команды и не отправляет отчёты разработчику.");
    }
    private void open(String pkg) {
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i==null){toast("Установи приложение: "+pkg);return;}
        try{startActivity(i);}catch(Exception e){toast("Не удалось открыть: "+pkg);}
    }
    @Override protected void onResume(){super.onResume();
        if(results!=null && tab==1)
          results.setText(getPreferences(MODE_PRIVATE).getString("scan","Проверка ещё не запускалась."));
    }
}