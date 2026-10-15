package com.yohaku.verification;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.TextView;
import java.io.InputStream;
import java.security.MessageDigest;

/** A separate process/UID proves the sender granted reads without granting writes. */
public class ReadOnlyViewer extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);TextView result=new TextView(this);result.setContentDescription("media-verification-result");result.setTextSize(18);result.setPadding(24,48,24,24);setContentView(result);
        try {
            Uri uri=getIntent().getData();String name="";long size=0;
            try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){cursor.moveToFirst();name=cursor.getString(0);size=cursor.getLong(1);}
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
            try(InputStream input=getContentResolver().openInputStream(uri)){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){digest.update(buffer,0,count);bytes+=count;}}
            StringBuilder checksum=new StringBuilder();for(byte value:digest.digest())checksum.append(String.format("%02x",value));
            boolean blocked=false;try{android.os.ParcelFileDescriptor write=getContentResolver().openFileDescriptor(uri,"w");if(write!=null)write.close();}catch(SecurityException expected){blocked=true;}
            result.setText("READ_OK\nNAME="+name+"\nMIME="+getContentResolver().getType(uri)+"\nSIZE="+size+"\nBYTES="+bytes+"\nSHA256="+checksum+"\nWRITE_BLOCKED="+blocked+"\nUID="+android.os.Process.myUid());
        }catch(Exception error){result.setText("READ_FAILED\n"+error);}
    }
}
