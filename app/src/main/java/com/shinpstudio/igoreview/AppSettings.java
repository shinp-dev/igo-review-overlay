package com.shinpstudio.igoreview;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

final class AppSettings {
    private final SharedPreferences p;
    AppSettings(Context c) { p=c.getSharedPreferences("settings",Context.MODE_PRIVATE); }
    String endpoint(){return p.getString("endpoint","");}
    String token(){return p.getString("token","");}
    int visits(){return p.getInt("visits",200);}
    String customModelHash(){return p.getString("custom_model_sha256","");}
    String customModelLabel(){return p.getString("custom_model_label","");}
    void setCustomModel(String sha,String label){
        p.edit().putString("custom_model_sha256",sha).putString("custom_model_label",label).apply();
    }
    void clearCustomModel(){
        p.edit().remove("custom_model_sha256").remove("custom_model_label").apply();
    }
    int candidates(){return p.getInt("candidates",3);}
    int metric(){return p.getInt("metric",0);}
    int opacity(){return p.getInt("opacity",85);}
    boolean auto(){return p.getBoolean("auto",true);}
    boolean show(){return p.getBoolean("show",true);}
    void show(boolean b){p.edit().putBoolean("show",b).apply();}
    void save(String endpoint,String token,int visits,int candidates,int metric,int opacity,boolean auto){
        p.edit().putString("endpoint",endpoint.trim()).putString("token",token.trim())
         .putInt("visits",visits).putInt("candidates",candidates)
         .putInt("metric",metric).putInt("opacity",opacity).putBoolean("auto",auto).apply();
    }
    Rect grid(int w,int h){
        if(!p.contains("gl")){
            int d=Math.round(w*.875f),l=(w-d)/2,t=Math.round(h*.237f);
            return new Rect(l,t,l+d,t+d);
        }
        return new Rect(Math.round(p.getFloat("gl",0)*w),Math.round(p.getFloat("gt",0)*h),
            Math.round(p.getFloat("gr",1)*w),Math.round(p.getFloat("gb",1)*h));
    }
    void saveGrid(Rect r,int w,int h){
        p.edit().putFloat("gl",(float)r.left/w).putFloat("gt",(float)r.top/h)
          .putFloat("gr",(float)r.right/w).putFloat("gb",(float)r.bottom/h).apply();
    }
    void resetGrid(){p.edit().remove("gl").remove("gt").remove("gr").remove("gb").apply();}
}
