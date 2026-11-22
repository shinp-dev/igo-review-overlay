package com.shinpstudio.igoreview;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Imports a user-selected genuine KataGo .bin.gz file, never from a remote URL. */
final class ModelInstaller {
    private static final long MAX_BYTES=700L*1024*1024;
    static final class Result {
        final String sha256,label;
        Result(String sha256,String label){this.sha256=sha256;this.label=label;}
    }
    static Result importModel(Context context,Uri uri)throws Exception{
        String label="custom-model.bin.gz";
        try(Cursor cursor=context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                String name=cursor.getString(0);
                if(name!=null)label=name;
            }
        }
        if(!label.toLowerCase(java.util.Locale.ROOT).endsWith(".bin.gz"))
            throw new IllegalArgumentException("KataGoの.bin.gzモデルを選んでください");
        File dir=new File(context.getNoBackupFilesDir(),"katago");
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("モデルの保存先を用意できません");
        File temp=new File(dir,"custom-model.partial");
        File installed=new File(dir,"custom-model.bin.gz");
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        long count=0;
        try{
            try(InputStream in=context.getContentResolver().openInputStream(uri);
                FileOutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IllegalStateException("モデルを開けません");
                byte[] buffer=new byte[65536];
                int n;
                while((n=in.read(buffer))!=-1){
                    count+=n;
                    if(count>MAX_BYTES)throw new IllegalArgumentException("モデルの容量が大きすぎます");
                    md.update(buffer,0,n);
                    out.write(buffer,0,n);
                }
            }
            if(count<1024)throw new IllegalArgumentException("モデルファイルが小さすぎます");
            try(InputStream input=java.nio.file.Files.newInputStream(temp.toPath())){
                if(input.read()!=0x1f||input.read()!=0x8b)
                    throw new IllegalArgumentException("gzip形式ではありません");
            }
            if(installed.exists()&&!installed.delete())
                throw new IllegalStateException("以前のモデルを入れ替えできません");
            if(!temp.renameTo(installed))
                throw new IllegalStateException("モデルの保存に失敗しました");
            StringBuilder hex=new StringBuilder();
            for(byte b:md.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return new Result(hex.toString(),label.length()>120?label.substring(0,120):label);
        }finally{temp.delete();}
    }
}
