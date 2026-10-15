package com.yohaku.verification;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Separate-UID fixtures for the real Android document picker; never part of the product APK. */
public class FixtureDocuments extends DocumentsProvider {
    private File directory;
    private final String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    @Override public boolean onCreate() {
        directory=new File(getContext().getFilesDir(),"fixtures");directory.mkdirs();
        try {
            for(int i=1;i<=2;i++) {
                File file=new File(directory,"receipt-"+i+".png");
                Bitmap bitmap=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);bitmap.eraseColor(i==1?Color.rgb(240,225,190):Color.rgb(195,220,235));
                try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
            }
            try(OutputStream out=new FileOutputStream(new File(directory,"notes.yohaku"))){out.write("Yohaku read-only verification\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            try(OutputStream out=new FileOutputStream(new File(directory,"broken.jpg"))){out.write(new byte[]{1,2,3});}
            try(OutputStream out=new FileOutputStream(new File(directory,"unknown.yohaku"))){out.write(new byte[]{4,5,6});}
            int samples=8000*8;ByteBuffer wave=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
            wave.put("RIFF".getBytes());wave.putInt(36+samples*2);wave.put("WAVEfmt ".getBytes());wave.putInt(16);wave.putShort((short)1);wave.putShort((short)1);wave.putInt(8000);wave.putInt(16000);wave.putShort((short)2);wave.putShort((short)16);wave.put("data".getBytes());wave.putInt(samples*2);
            for(int i=0;i<samples;i++)wave.putShort((short)(Math.sin(i*2*Math.PI*440/8000)*1500));
            try(OutputStream out=new FileOutputStream(new File(directory,"tone.wav"))){out.write(wave.array());}
            return true;
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    private String mime(String name) {if(name.equals("unknown.yohaku"))return "application/x-yohaku-no-viewer";if(name.equals("root"))return DocumentsContract.Document.MIME_TYPE_DIR;if(name.endsWith(".png"))return "image/png";if(name.endsWith(".jpg"))return "image/jpeg";if(name.endsWith(".wav"))return "audio/wav";return "application/x-yohaku-verification";}
    private MatrixCursor cursor(String[] projection) {return new MatrixCursor(projection==null?columns:projection);}
    private void add(MatrixCursor cursor,String id) {
        File file=new File(directory,id);MatrixCursor.RowBuilder row=cursor.newRow();
        for(String column:cursor.getColumnNames())switch(column) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row.add(id);break;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row.add(id.equals("root")?"Verification files":id);break;
            case DocumentsContract.Document.COLUMN_MIME_TYPE:row.add(mime(id));break;
            case DocumentsContract.Document.COLUMN_FLAGS:row.add(0);break;
            case DocumentsContract.Document.COLUMN_SIZE:row.add(id.equals("root")?0:file.length());break;
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED:row.add(file.lastModified());break;
            default:row.add(null);
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] roots={DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES};
        MatrixCursor cursor=new MatrixCursor(projection==null?roots:projection);MatrixCursor.RowBuilder row=cursor.newRow();
        for(String column:cursor.getColumnNames())switch(column) {
            case DocumentsContract.Root.COLUMN_ROOT_ID:row.add("verification");break;
            case DocumentsContract.Root.COLUMN_DOCUMENT_ID:row.add("root");break;
            case DocumentsContract.Root.COLUMN_TITLE:row.add("Yohaku Verification Files");break;
            case DocumentsContract.Root.COLUMN_FLAGS:row.add(DocumentsContract.Root.FLAG_LOCAL_ONLY);break;
            case DocumentsContract.Root.COLUMN_MIME_TYPES:row.add("*/*");break;
            default:row.add(null);
        }return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection) {MatrixCursor cursor=cursor(projection);add(cursor,id);return cursor;}
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort) {MatrixCursor cursor=cursor(projection);for(String name:new String[]{"receipt-1.png","receipt-2.png","tone.wav","notes.yohaku","broken.jpg","unknown.yohaku"})add(cursor,name);return cursor;}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!mode.equals("r"))throw new SecurityException("Fixtures are read-only");
        File file=new File(directory,id);if(!file.getParentFile().equals(directory))throw new SecurityException();return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
