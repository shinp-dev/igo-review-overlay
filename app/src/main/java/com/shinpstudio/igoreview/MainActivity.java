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
    private static final int MODEL_REQUEST=1002;
    private final int[] visitValues={50,100,200,500,1000};
    private AppSettings settings;
    private Spinner visits,candidates,metric,model,power;
    private SeekBar opacity,fontSize;
    private CheckBox autoRefresh;
    private TextView modelStatus;
    private Button importModelButton;

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
        section(root,"端末内KataGo");
        note(root,"KataGo CPUエンジン、軽量モデル、9路専用モデルをAPKに同梱。PC・通信は不要です。9路専用モデルは棋力重視ですが、処理時間・発熱・メモリ使用量が増える可能性があります。");
        model=spinner(root,"使用するAIモデル",modelOptions(),settings.modelChoice());
        modelStatus=new TextView(this);
        modelStatus.setTextSize(13);
        modelStatus.setPadding(0,dp(9),0,dp(5));
        root.addView(modelStatus);
        refreshModelStatus();
        importModelButton=new Button(this);
        importModelButton.setText("KataGoモデル (.bin.gz) を取り込む");
        importModelButton.setAllCaps(false);
        importModelButton.setOnClickListener(v->{
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            startActivityForResult(picker,MODEL_REQUEST);
        });
        root.addView(importModelButton,full());
        button(root,"同梱の軽量モデルに戻す",()->{
            settings.clearCustomModel();
            refreshModelPicker();
        });
        section(root,"解析設定");
        power=spinner(root,"オーバーヒート設定（探索スレッド数）",
            new String[]{"通常：2スレッド","高負荷：4スレッド","最大負荷：6スレッド"},
            settings.searchThreads()==6?2:settings.searchThreads()==4?1:0);
        note(root,"高負荷・最大負荷は速度向上を保証しません。熱・電池消費が増える可能性があります。設定は次回の解析開始時に反映されます。");
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
        TextView fontSizeText=new TextView(this);
        fontSizeText.setTextColor(Color.DKGRAY);
        root.addView(fontSizeText);
        fontSize=new SeekBar(this);
        fontSize.setMax(8); // 70%, 80%, ... 150%
        fontSize.setProgress((settings.fontScalePercent()-70)/10);
        fontSizeText.setText("評価値の文字サイズ: "+settings.fontScalePercent()+"%");
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                fontSizeText.setText("評価値の文字サイズ: "+(70+value*10)+"%");
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        root.addView(fontSize);
        button(root,"盤面枠の位置をリセット",()->{
            settings.resetGrid();Toast.makeText(this,"次回開始時に枠を初期化します",Toast.LENGTH_SHORT).show();
        });
        note(root,"開始後、「枠調整」で左上・右下のハンドルを9路盤の外周交点に合わせ、「調整完了」を押してください。盤面上の緑バーで評価値をオン／オフできます。");
        button(root,"設定を保存",this::save);
        button(root,"盤面解析を開始",()->{
            save();
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
    private void refreshModelStatus(){
        if(modelStatus==null)return;
        String label=settings.customModelLabel();
        String active=settings.modelChoice()==1?"同梱の9路専用 b18":settings.modelChoice()==2?
            label+"（取り込み済み）":"同梱の軽量 g170 b6c96";
        modelStatus.setText("選択中: "+active);
    }
    private String[] modelOptions(){
        if(settings.customModelHash().isEmpty())
            return new String[]{"軽量 g170 b6c96（高速）","9路専用 b18c384nbt（高精度・高負荷）"};
        return new String[]{"軽量 g170 b6c96（高速）","9路専用 b18c384nbt（高精度・高負荷）",
            "取り込みモデル: "+settings.customModelLabel()};
    }
    private void refreshModelPicker(){
        if(model!=null){
            model.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modelOptions()));
            model.setSelection(settings.modelChoice());
        }
        refreshModelStatus();
    }
    private void importNetwork(Uri uri){
        importModelButton.setEnabled(false);
        modelStatus.setText("モデルを検証・保存しています…");
        Thread worker=new Thread(()->{
            try{
                ModelInstaller.Result installed=ModelInstaller.importModel(this,uri);
                settings.setCustomModel(installed.sha256,installed.label);
                runOnUiThread(()->{
                    if(!isDestroyed()){
                        importModelButton.setEnabled(true);
                        refreshModelPicker();
                        Toast.makeText(this,"モデルを保存しました",Toast.LENGTH_LONG).show();
                    }
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(!isDestroyed()){
                        importModelButton.setEnabled(true);
                        refreshModelStatus();
                        Toast.makeText(this,"モデルの取り込みに失敗: "+e.getMessage(),Toast.LENGTH_LONG).show();
                    }
                });
            }
        },"import-katago-model");
        worker.setDaemon(true);worker.start();
    }
    private void save(){
        settings.save("", "",visitValues[visits.getSelectedItemPosition()],
            candidates.getSelectedItemPosition()+1,metric.getSelectedItemPosition(),
            opacity.getProgress()+20,autoRefresh.isChecked());
        settings.setFontScalePercent(70+fontSize.getProgress()*10);
        settings.setSearchThreads(new int[]{2,4,6}[power.getSelectedItemPosition()]);
        settings.setModelChoice(model.getSelectedItemPosition());
        refreshModelStatus();
        Toast.makeText(this,"設定を保存しました",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==MODEL_REQUEST&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            importNetwork(data.getData());
            return;
        }
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
