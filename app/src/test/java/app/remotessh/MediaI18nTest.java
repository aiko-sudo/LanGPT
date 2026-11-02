package app.remotessh;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class MediaI18nTest {
  private Context context(Locale locale) {
    Context base=RuntimeEnvironment.getApplication();
    Configuration config=new Configuration(base.getResources().getConfiguration());
    config.setLocale(locale);
    return base.createConfigurationContext(config);
  }
  @Test public void localizedCodeControlsPreserveRemoteCodeAndLanguage() {
    for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.CHINESE}) {
      Context ctx=context(locale); String[] copied={null};
      MessageBodyView body=new MessageBodyView(ctx,0,0,0,v->{},v->copied[0]=v);
      String command="echo '中文 /tmp/a'\n";
      body.setSource("```sh\n"+command+"```\n```\n第二块\n```");
      LinearLayout first=(LinearLayout)body.getChildAt(0);
      LinearLayout header=(LinearLayout)first.getChildAt(0);
      assertEquals("sh",((TextView)header.getChildAt(0)).getText().toString());
      TextView copy=(TextView)header.getChildAt(1);
      assertEquals(ctx.getString(R.string.media_copy),copy.getText().toString());
      assertEquals(ctx.getString(R.string.media_copy_block,1),copy.getContentDescription());
      copy.performClick();assertEquals(command,copied[0]);
      LinearLayout second=(LinearLayout)body.getChildAt(1);
      LinearLayout header2=(LinearLayout)second.getChildAt(0);
      assertEquals(ctx.getString(R.string.media_text),((TextView)header2.getChildAt(0)).getText().toString());
      assertEquals(ctx.getString(R.string.media_copy_block,2),header2.getChildAt(1).getContentDescription());
      assertTrue(ctx.getString(R.string.media_swipe).startsWith(" · "));
      String path="/tmp/中文 %s.txt";
      assertTrue(ctx.getString(R.string.media_saved,path).contains(path));
      assertTrue(ctx.getString(R.string.media_download_failed,path).contains(path));
    }
  }
}
