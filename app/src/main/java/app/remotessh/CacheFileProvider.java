package app.remotessh;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;

/** Read-only access to cached downloads for external viewer apps; serves only <cache>/open. */
public final class CacheFileProvider extends ContentProvider {
  /** Must match android:authorities="${applicationId}.files" in the manifest. */
  static String authority(Context context) {return context.getPackageName()+".files";}

  static File openDir(Context context) {
    File base=context.getExternalCacheDir()!=null?context.getExternalCacheDir():context.getCacheDir();
    return new File(base,"open");
  }

  /** Builds the content URI for one cached download; each segment is encoded by appendPath. */
  static Uri uriFor(Context context, File root, File file) {
    String relative=root.toURI().relativize(file.toURI()).getPath();
    Uri.Builder builder=new Uri.Builder().scheme("content").authority(authority(context));
    for(String segment:relative.split("/")) if(!segment.isEmpty()) builder.appendPath(segment);
    return builder.build();
  }

  private File target(Uri uri) throws IOException {
    return CachePaths.resolve(openDir(getContext()),uri.getPath());
  }

  @Override public boolean onCreate() {return true;}
  @Override public String getType(Uri uri) {
    try {
      String name=target(uri).getName();int dot=name.lastIndexOf('.');
      String mime=dot>0?MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase()):null;
      return mime==null?"application/octet-stream":mime;
    }catch(IOException e){return null;}
  }
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
    try {
      File file=target(uri);
      String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
      MatrixCursor cursor=new MatrixCursor(columns);
      if(file.isFile()) {
        Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++)
          row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?file.getName()
              :OpenableColumns.SIZE.equals(columns[i])?Long.valueOf(file.length()):null;
        cursor.addRow(row);
      }
      return cursor;
    }catch(IOException e){return null;}
  }
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
    if(!"r".equals(mode))throw new FileNotFoundException("只读访问");
    try {
      File file=target(uri);
      if(!file.isFile())throw new FileNotFoundException("缓存文件不存在");
      return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }catch(IOException e){throw new FileNotFoundException(e.getMessage());}
  }
  @Override public int delete(Uri uri,String selection,String[] args) {return 0;}
  @Override public Uri insert(Uri uri,ContentValues values) {return null;}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] args) {return 0;}
}
