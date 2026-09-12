package dev.hazel.livealarm;

import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.util.UUID;

/** Bounded, private image copy. No broad media permission and no persistent URI dependency. */
public final class BackgroundStore {
    public static void importImage(Context c,Prefs prefs,Uri uri)throws IOException{
        File temp=File.createTempFile("background-", ".input",c.getCacheDir());
        File dest=new File(c.getFilesDir(),"background-"+UUID.randomUUID()+".jpg");
        Bitmap bitmap=null;
        try{
            try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException("图片不可读取");byte[] buffer=new byte[16384];int n;long total=0;
                while((n=in.read(buffer))!=-1){total+=n;if(total>20L*1024*1024)throw new IOException("请选择小于 20 MB 的图片");out.write(buffer,0,n);}
                if(total==0)throw new IOException("图片文件为空");
            }
            if(Build.VERSION.SDK_INT>=28){
                bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(temp),(decoder,info,source)->{
                    int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,1920f/Math.max(w,h));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));
                });
            }else{
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(temp.getPath(),options);
                if(options.outWidth<=0||options.outHeight<=0)throw new IOException("无法识别图片");
                options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1920)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;bitmap=BitmapFactory.decodeFile(temp.getPath(),options);
                if(bitmap!=null){
                    ExifInterface exif=new ExifInterface(temp);Matrix matrix=new Matrix();
                    if(exif.isFlipped())matrix.postScale(-1,1);matrix.postRotate(exif.getRotationDegrees());
                    if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}
                }
            }
            if(bitmap==null)throw new IOException("此图片无法解码，请选择 JPG、PNG 或 WebP");
            try(OutputStream out=new FileOutputStream(dest)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("图片保存失败");}
            String name="自选背景";
            try(Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(RuntimeException ignored){}
            if(name==null)name="自选背景";String old=prefs.raw().getString("backgroundPath","");
            if(!prefs.raw().edit().putString("backgroundPath",dest.getAbsolutePath()).putString("backgroundName",name.substring(0,Math.min(100,name.length()))).commit())throw new IOException("设置保存失败");
            deleteOwned(c,old);
        }catch(OutOfMemoryError e){dest.delete();throw new IOException("图片过大，请选择较小的图片",e);}
        catch(IOException|RuntimeException e){dest.delete();throw e;}
        finally{temp.delete();if(bitmap!=null)bitmap.recycle();}
    }
    public static void remove(Context c,Prefs p){String old=p.raw().getString("backgroundPath","");p.raw().edit().remove("backgroundPath").remove("backgroundName").commit();deleteOwned(c,old);}
    private static void deleteOwned(Context c,String path){if(path.isEmpty())return;File f=new File(path);if(c.getFilesDir().equals(f.getParentFile())&&f.getName().startsWith("background-"))f.delete();}
}
