package com.shinpstudio.igoreview;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.Color;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST=1001;
    private final int[] visitValues={50,100,200,500,1000};
    private AppSettings settings;
    private EditText endpoint,token;
    private Spinner visits,candidates,metric;
    private SeekBar opacity;
    private CheckBox autoRefresh;

    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        settings=new AppSettings(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(42),dp(20),dp(32));
        root.setBackgroundColor(0xfff4f8fb);
        scroll.addView(root);
        TextView heading=new TextView(this);
        heading.setText("囲碁クエスト 9路解析");heading.setTextSize(24);heading.setTextColor(0xff12435e);
        root.addView(heading);
        note(root,"終局後の振り返り用。囲碁クエストに重ねてKataGoの候補手を一手ずつ表示します。");
        section(root,"KataGo接続先");
        endpoint=new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setHint("http://PCのLAN内IP:8765");
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(settings.endpoint());root.addView(endpoint,full());
        token=new EditText(this);
        token.setSingleLine(true);
        token.setHint("接続トークン（必要な場合）");
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(settings.token());root.addView(token,full());
        note(root,"初期版のKataGoはPC側ブリッジで実行します。スマホから送信するのは81交点の石配置だけです。");
        section(root,"解析設定");
        visits=spinner(root,"探索回数",new String[]{"軽量 50","軽量 100","標準 200","高精度 500","高精度 1000"},visitIndex());
        candidates=spinner(root,"候補手の数",new String[]{"1手","2手","3手","4手","5手"},settings.candidates()-1);
        metric=spinner(root,"表示する評価値",new String[]{"勝率","目数差","勝率＋目数差"},settings.metric());
        autoRefresh=new CheckBox(this);
        autoRefresh.setText("局面変更時に自動更新");autoRefresh.setChecked(settings.auto());
        root.addView(autoRefresh);
        section(root,"オーバーレイ");
        TextView opacityText=new TextView(this);
        root.addView(opacityText);
        opacity=new SeekBar(this);
        opacity.setMax(80);opacity.setProgress(Math.max(0,settings.opacity()-20));
        opacityText.setText("表示の不透明度: "+settings.opacity()+"%");
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int v,boolean fromUser){
                opacityText.setText("表示の不透明度: "+(v+20)+"%");
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        root.addView(opacity);
        button(root,"盤面枠の位置をリセット",()->{
            settings.resetGrid();Toast.makeText(this,"次回開始時に枠を初期化します",Toast.LENGTH_SHORT).show();
        });
        note(root,"開始後、左上・右下のハンドルを9路盤の最外交点に合わせてください。右側余白の色付き帯をタップすると評価値をオン／オフできます。");
        button(root,"設定を保存",this::save);
        button(root,"盤面解析を開始",()->{
            save();
            if(settings.endpoint().isEmpty()){
                Toast.makeText(this,"KataGo接続先のURLを入力してください",Toast.LENGTH_LONG).show();return;
            }
            if(!Settings.canDrawOverlays(this)){
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:"+getPackageName())));return;
            }
            stopService(new Intent(this,ReviewOverlayService.class));
            MediaProjectionManager mgr=getSystemService(MediaProjectionManager.class);
            startActivityForResult(mgr.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()),CAPTURE_REQUEST);
        });
        setContentView(scroll);
    }
    private void save(){
        String url=endpoint.getText().toString().trim().replaceAll("/+$","");
        settings.save(url,token.getText().toString(),visitValues[visits.getSelectedItemPosition()],
            candidates.getSelectedItemPosition()+1,metric.getSelectedItemPosition(),
            opacity.getProgress()+20,autoRefresh.isChecked());
        Toast.makeText(this,"設定を保存しました",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==CAPTURE_REQUEST&&result==RESULT_OK&&data!=null){
            startForegroundService(new Intent(this,ReviewOverlayService.class)
                .putExtra(ReviewOverlayService.EXTRA_CAPTURE,data));
            moveTaskToBack(true);
        }
    }
    private int visitIndex(){for(int i=0;i<visitValues.length;i++)if(visitValues[i]==settings.visits())return i;return 2;}
    private Spinner spinner(LinearLayout root,String title,String[] choices,int index){
        TextView label=new TextView(this);
        label.setText(title);label.setPadding(0,dp(9),0,0);root.addView(label);
        Spinner spin=new Spinner(this);
        spin.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));
        spin.setSelection(Math.max(0,Math.min(choices.length-1,index)));
        root.addView(spin,full());return spin;
    }
    private void section(LinearLayout root,String t){
        TextView v=new TextView(this);v.setText(t);v.setTextSize(17);v.setTextColor(0xff20597a);
        v.setPadding(0,dp(18),0,dp(6));root.addView(v);
    }
    private void note(LinearLayout root,String t){
        TextView v=new TextView(this);v.setText(t);v.setTextSize(13);v.setTextColor(Color.DKGRAY);
        v.setPadding(0,dp(7),0,dp(9));root.addView(v);
    }
    private void button(LinearLayout root,String t,Runnable fn){
        Button v=new Button(this);v.setAllCaps(false);v.setText(t);v.setOnClickListener(w->fn.run());
        root.addView(v,full());
    }
    private LinearLayout.LayoutParams full(){
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
}
