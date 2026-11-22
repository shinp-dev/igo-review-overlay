package com.shinpstudio.igoreview;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Foreground screen capture service with separate pass-through drawing window and
 * a narrow touchable strip to the RIGHT of the 9x9 grid.
 */
public final class ReviewOverlayService extends Service {
    static final String EXTRA_CAPTURE="capture_data";
    private static final String ACTION_STOP="com.shinpstudio.igoreview.STOP";
    private static final String CHANNEL="igo_review";
    private static final String TAG="IgoReview";
    private static final int TICK_MS=400;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService engine=Executors.newSingleThreadExecutor();
    private final List<View> windows=new ArrayList<>();
    private final Rect frame=new Rect();
    private AppSettings settings;
    private GoAnalysisEngine analysisEngine;
    private Context overlayContext;
    private WindowManager manager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private int screenWidth,screenHeight;
    private View toggleZone;
    private WindowManager.LayoutParams toggleParams;
    private TextView status,turnButton;
    private BoardDraw drawing;
    private BoardRecognizer.Position pending,stable,evaluated;
    private KataGoClient.Result result;
    private int confirmations,misses,turnOverride=BoardRecognizer.UNKNOWN;
    private int generation;
    private Future<?> task;
    private boolean dragging,shown;
    private long lastSample;
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback(){
        @Override public void onStop(){ui.post(ReviewOverlayService.this::stopSelf);}
        @Override public void onCapturedContentResize(int width,int height){
            if(width!=screenWidth||height!=screenHeight){
                ui.post(()->{message("画面サイズが変わりました。解析を再開始してください");stopSelf();});
            }
        }
    };

    @Override public IBinder onBind(Intent i){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        Intent consent=intent.getParcelableExtra(EXTRA_CAPTURE,Intent.class);
        if(consent==null){stopSelf();return START_NOT_STICKY;}
        try{
            settings=new AppSettings(this);
            analysisEngine=new NativeGoAnalysisEngine(this,settings);
            startForeground(1,notification());
            startCapture(consent);
            buildWindows();
        }catch(Exception error){
            Log.e(TAG,"Cannot start review overlay",error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(){
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"9路盤面解析",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,ReviewOverlayService.class)
            .setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
            .setContentTitle("囲碁9路 解析オーバーレイ")
            .setContentText("画面共有中。終了は通知から操作できます")
            .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"終了",stop).build()).build();
    }

    private void startCapture(Intent consent){
        Display display=getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        overlayContext=createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        manager=overlayContext.getSystemService(WindowManager.class);
        Rect bounds=manager.getMaximumWindowMetrics().getBounds();
        screenWidth=bounds.width();screenHeight=bounds.height();
        frame.set(settings.grid(screenWidth,screenHeight));
        captureThread=new HandlerThread("igo-review-capture");
        captureThread.start();captureHandler=new Handler(captureThread.getLooper());
        reader=ImageReader.newInstance(screenWidth,screenHeight,PixelFormat.RGBA_8888,3);
        projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
        if(projection==null)throw new IllegalStateException("画面共有を開始できません");
        projection.registerCallback(projectionCallback,ui);
        virtualDisplay=projection.createVirtualDisplay("IgoReview",screenWidth,screenHeight,
            overlayContext.getResources().getConfiguration().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,captureHandler);
        if(virtualDisplay==null)throw new IllegalStateException("仮想画面を作成できません");
        reader.setOnImageAvailableListener(this::readFrame,captureHandler);
    }

    private void buildWindows(){
        shown=settings.show();
        drawing=new BoardDraw(overlayContext);
        WindowManager.LayoutParams canvasParams=params(-1,-1,"IgoAnalysisDisplay");
        canvasParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        // Android untrusted touch overlay limit: keep this window below 0.8.
        canvasParams.alpha=.74f;
        add(drawing,canvasParams);
        addHandle(true);
        addHandle(false);

        // Deliberately keep the touch target outside the grid. Everything else passes through.
        toggleZone=new View(overlayContext);
        toggleZone.setOnClickListener(v->{
            shown=!shown;settings.show(shown);
            updateToggle();drawing.invalidate();
            if(shown&&stable!=null&&(result==null||!stable.same(evaluated)))analyze(stable);
            if(!shown)message("評価値は非表示（右端の帯をタップで再表示）");
        });
        toggleParams=params(dp(15),dp(70),"IgoRightEdgeToggle");
        positionToggle();
        add(toggleZone,toggleParams);

        LinearLayout panel=new LinearLayout(overlayContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(8),dp(5),dp(8),dp(5));
        panel.setBackground(round(0xee233749,dp(10)));
        status=new TextView(overlayContext);
        status.setText("9路の盤面を読み取り中…");status.setTextColor(Color.WHITE);
        status.setTextSize(12);status.setGravity(Gravity.CENTER);
        panel.addView(status);
        LinearLayout row=new LinearLayout(overlayContext);
        row.setGravity(Gravity.CENTER);
        panel.addView(row);
        Button side=new Button(overlayContext);
        turnButton=side;
        side.setAllCaps(false);side.setText("手番: 自動");side.setTextSize(11);
        side.setOnClickListener(v->{
            turnOverride=turnOverride==BoardRecognizer.UNKNOWN?BoardRecognizer.BLACK:
                turnOverride==BoardRecognizer.BLACK?BoardRecognizer.WHITE:BoardRecognizer.UNKNOWN;
            side.setText(turnOverride==BoardRecognizer.UNKNOWN?"手番: 自動":
                turnOverride==BoardRecognizer.BLACK?"手番: 黒":"手番: 白");
            if(stable!=null){
                int turn=turnOverride==BoardRecognizer.UNKNOWN?stable.turn:turnOverride;
                if(turn!=BoardRecognizer.UNKNOWN)analyze(new BoardRecognizer.Position(stable.stones,stable.last,turn));
            }
        });
        row.addView(side,buttonWidth());
        Button refresh=new Button(overlayContext);
        refresh.setAllCaps(false);refresh.setText("解析");refresh.setTextSize(12);
        refresh.setOnClickListener(v->{
            if(stable!=null){
                int turn=turnOverride==BoardRecognizer.UNKNOWN?stable.turn:turnOverride;
                if(turn!=BoardRecognizer.UNKNOWN)analyze(new BoardRecognizer.Position(stable.stones,stable.last,turn));
                else message("最終着手が読み取れません。手番を選択してください");
            }
        });
        row.addView(refresh,buttonWidth());
        Button stop=new Button(overlayContext);
        stop.setAllCaps(false);stop.setText("終了");stop.setTextSize(12);
        stop.setOnClickListener(v->stopSelf());
        row.addView(stop,buttonWidth());
        WindowManager.LayoutParams panelParams=params(
            Math.min(dp(360),screenWidth-dp(12)),-2,"IgoReviewControls");
        panelParams.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;
        panelParams.y=Math.min(Math.round(screenHeight*.83f),screenHeight-dp(95));
        add(panel,panelParams);
    }

    private LinearLayout.LayoutParams buttonWidth(){
        return new LinearLayout.LayoutParams(0,-2,1f);
    }
    private WindowManager.LayoutParams params(int w,int h,String title){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle(title);
        return p;
    }
    private void add(View view,WindowManager.LayoutParams p){
        manager.addView(view,p);windows.add(view);
    }
    private void addHandle(boolean upper){
        TextView knob=new TextView(overlayContext);
        knob.setText(upper?"↖":"↘");knob.setTextSize(17);
        knob.setTextColor(Color.WHITE);knob.setGravity(Gravity.CENTER);
        knob.setBackground(round(0xff177fa6,dp(9)));
        knob.setContentDescription(upper?"左上交点を移動":"右下交点を移動");
        WindowManager.LayoutParams p=params(dp(30),dp(30),upper?"IgoTopLeft":"IgoBottomRight");
        moveHandle(p,upper);
        knob.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY;int oldX,oldY;
            @Override public boolean onTouch(View v,MotionEvent event){
                switch(event.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        dragging=true;downX=event.getRawX();downY=event.getRawY();
                        oldX=upper?frame.left:frame.right;oldY=upper?frame.top:frame.bottom;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int x=oldX+Math.round(event.getRawX()-downX);
                        int y=oldY+Math.round(event.getRawY()-downY);
                        if(upper){
                            frame.left=clamp(x,0,frame.right-dp(240));
                            frame.top=clamp(y,0,frame.bottom-dp(240));
                        }else{
                            frame.right=clamp(x,frame.left+dp(240),screenWidth-1);
                            frame.bottom=clamp(y,frame.top+dp(240),screenHeight-1);
                        }
                        moveHandle(p,upper);manager.updateViewLayout(v,p);
                        positionToggle();drawing.invalidate();
                        clearPosition();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging=false;
                        settings.saveGrid(frame,screenWidth,screenHeight);
                        return true;
                    default:return true;
                }
            }
        });
        add(knob,p);
    }
    private void moveHandle(WindowManager.LayoutParams p,boolean upper){
        p.x=clamp((upper?frame.left:frame.right)-p.width/2,0,screenWidth-p.width);
        p.y=clamp((upper?frame.top:frame.bottom)-p.height/2,0,screenHeight-p.height);
    }
    private void positionToggle(){
        if(toggleZone==null)return;
        int left=frame.right+dp(2),right=screenWidth-dp(2);
        int available=right-left;
        if(available<dp(8)){toggleZone.setVisibility(View.GONE);return;}
        int w=Math.min(dp(46),available);
        int h=Math.min(frame.height()-dp(55),Math.round(screenHeight*.38f));
        if(h<dp(50))h=dp(50);
        toggleParams.width=w;toggleParams.height=h;
        toggleParams.x=left+(available-w)/2;
        toggleParams.y=clamp(frame.centerY()-h/2,0,screenHeight-h);
        toggleZone.setVisibility(View.VISIBLE);
        updateToggle();
        if(toggleZone.getParent()!=null)manager.updateViewLayout(toggleZone,toggleParams);
    }
    private void updateToggle(){
        if(toggleZone!=null){
            toggleZone.setBackground(round(shown?0x8860caa5:0x88878b91,dp(8)));
            toggleZone.setContentDescription(shown?"評価値を非表示にする":"評価値を表示する");
        }
    }
    private void readFrame(ImageReader source){
        try(Image image=source.acquireLatestImage()){
            if(image==null||dragging)return;
            long now=SystemClock.elapsedRealtime();
            if(now-lastSample<TICK_MS)return;
            lastSample=now;
            Rect snapshot=new Rect(frame);
            try{
                BoardRecognizer.Position p=BoardRecognizer.read(image,snapshot);
                ui.post(()->{if(!dragging&&snapshot.equals(frame))received(p);});
            }catch(IllegalArgumentException e){
                ui.post(()->{if(++misses>=6){clearPosition();message(e.getMessage());}});
            }catch(RuntimeException e){
                Log.w(TAG,"Board capture failed",e);
            }
        }catch(RuntimeException e){
            Log.w(TAG,"Image read failure",e);
        }
    }
    private void received(BoardRecognizer.Position p){
        misses=0;
        if(!p.sameStones(pending)){
            pending=p;confirmations=1;return;
        }
        if(++confirmations<2)return;
        int turn=turnOverride!=BoardRecognizer.UNKNOWN?turnOverride:
            BoardRecognizer.inferTurn(stable,p);
        if(turn==BoardRecognizer.UNKNOWN){
            // Never leave scores from a DIFFERENT position on screen.
            if(!p.sameStones(stable)){
                generation++;
                if(task!=null)task.cancel(true);
                result=null;evaluated=null;drawing.invalidate();
            }
            // Retain this snapshot so a later single-stone transition can infer turn.
            stable=p;message("手番が不明です。次の一手を待つか手番を選んでください");
            return;
        }
        BoardRecognizer.Position next=new BoardRecognizer.Position(p.stones,p.last,turn);
        if(next.same(stable))return;
        stable=next;
        if(settings.auto()&&shown)analyze(next);
        else {
            generation++;if(task!=null)task.cancel(true);
            result=null;evaluated=null;drawing.invalidate();
            message(turn==BoardRecognizer.BLACK?"黒番 · 自動解析停止中":"白番 · 自動解析停止中");
        }
    }
    private void analyze(BoardRecognizer.Position p){
        generation++;
        final int expected=generation;
        if(task!=null)task.cancel(true);
        evaluated=null;
        result=null;drawing.invalidate();
        message("KataGoで解析中…");
        task=engine.submit(()->{
            try{
                KataGoClient.Result computed=analysisEngine.analyze(p,p.turn);
                ui.post(()->{
                    if(generation!=expected)return;
                    evaluated=p;result=computed;drawing.invalidate();
                    String color=p.turn==BoardRecognizer.BLACK?"黒":"白";
                    message(String.format(Locale.JAPAN,"%s番 勝率 %.1f%% / 形勢 %+.1f目",
                        color,computed.winrate*100,computed.score));
                });
            }catch(Exception e){
                ui.post(()->{
                    if(generation!=expected)return;
                    Log.w(TAG,"KataGo analysis failed",e);
                    message("解析エラー: "+(e.getMessage()==null?"接続を確認":e.getMessage()));
                });
            }
        });
    }
    private void clearPosition(){
        pending=null;stable=null;confirmations=0;
        generation++;if(task!=null)task.cancel(true);
        result=null;evaluated=null;
        if(drawing!=null)drawing.invalidate();
    }
    private void message(String text){if(status!=null&&!text.contentEquals(status.getText()))status.setText(text);}
    private GradientDrawable round(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;
    }
    private int dp(int x){return Math.round(x*overlayContext.getResources().getDisplayMetrics().density);}
    private float dp(float x){return x*overlayContext.getResources().getDisplayMetrics().density;}
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}

    @Override public void onConfigurationChanged(Configuration c){
        super.onConfigurationChanged(c);
        if(manager!=null){
            Rect r=manager.getMaximumWindowMetrics().getBounds();
            if(r.width()!=screenWidth||r.height()!=screenHeight)stopSelf();
        }
    }
    @Override public void onDestroy(){
        generation++;if(task!=null)task.cancel(true);engine.shutdownNow();
        // A blocking GTP read must not run on the main thread during teardown.
        // Stop the native process from the existing worker when possible.
        GoAnalysisEngine retired = analysisEngine;
        if(retired!=null){
            Thread shutdown=new Thread(retired::close,"katago-overlay-shutdown");
            shutdown.setDaemon(true);shutdown.start();
        }
        if(reader!=null){reader.setOnImageAvailableListener(null,null);reader.close();reader=null;}
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        if(projection!=null){projection.unregisterCallback(projectionCallback);projection.stop();projection=null;}
        if(captureThread!=null)captureThread.quitSafely();
        if(manager!=null)for(View view:windows)manager.removeViewImmediate(view);
        windows.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private final class BoardDraw extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF chip=new RectF();
        private final int[] origin=new int[2];
        BoardDraw(Context c){super(c);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            getLocationOnScreen(origin);
            canvas.save();canvas.translate(-origin[0],-origin[1]);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));
            paint.setColor(0x8835b6e4);
            canvas.drawRect(frame,paint);
            canvas.restore();
            if(!shown||result==null||evaluated==null)return;
            canvas.save();canvas.translate(-origin[0],-origin[1]);
            float sx=frame.width()/8f,sy=frame.height()/8f;
            int max=Math.min(settings.candidates(),result.moves.size());
            for(int i=0;i<max;i++){
                KataGoClient.Candidate move=result.moves.get(i);
                int row=move.index/9,col=move.index%9;
                float cx=frame.left+col*sx,cy=frame.top+row*sy;
                // Keep markers INSIDE the recognizer's 0.325-grid sampling annulus.
                // Otherwise MediaProjection may re-read our overlay as stones.
                float radius=Math.min(sx,sy)*.24f;
                int shade=i==0?0xff0f815c:0xff213a55;
                int alpha=Math.round(settings.opacity()*2.55f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((alpha<<24)|(shade&0xffffff));
                canvas.drawCircle(cx,cy,radius,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2f));
                paint.setColor(i==0?0xff48f6ab:0xfffbd38c);
                canvas.drawCircle(cx,cy,radius,paint);
                paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);paint.setColor(Color.WHITE);
                int metric=settings.metric();
                if(metric==0||metric==2){
                    paint.setTextSize(Math.min(dp(13f),sy*.20f));
                    String win=String.format(Locale.JAPAN,"%.0f%%",move.winrate*100);
                    canvas.drawText(win,cx,cy+(metric==2?-.025f: .07f)*sy,paint);
                }
                if(metric==1||metric==2){
                    paint.setTextSize(Math.min(dp(12f),sy*.18f));
                    String score=String.format(Locale.JAPAN,"%+.1f",move.score);
                    canvas.drawText(score,cx,cy+(metric==2?.17f:.06f)*sy,paint);
                }
            }
            canvas.restore();
        }
    }
}
