package org.cache2k.core;

/*
 * #%L
 * cache2k implementation
 * %%
 * Copyright (C) 2000 - 2019 headissue GmbH, Munich
 * %%
 * ;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.testing.category.FastTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * @author Jens Wilke
 */
@Category(FastTests.class)
public class EntryTest {

  @Test
  public void testLastModifiedStoresValue() {
    Entry e = new Entry();
    synchronized (e) {
      e.setRefreshTime(4711);
      assertEquals(4711, e.getRefreshTime());
      e.setRefreshTime(123456);
      assertEquals(123456, e.getRefreshTime());
    }
  }

  @Test
  public void testLastModified50YearsRange() {
    long _50yearsMillis = 50L * 365 * 24 * 60 * 60 * 1000;
    long t = System.currentTimeMillis() + _50yearsMillis;
    Entry e = new Entry();
    synchronized (e) {
      e.setRefreshTime(t);
      assertEquals(t, e.getRefreshTime());
    }
  }

  @Test
  public void testLastModifiedMaxRange() {
    Entry e = new Entry();
    synchronized (e) {
      e.setRefreshTime(Long.MAX_VALUE);
      SimpleDateFormat df = new SimpleDateFormat("yyyy-MMM-dd HH:mm:ss");
      df.setTimeZone(TimeZone.getTimeZone("GMT"));
      assertEquals("2248-Sep-26 15:10:22", df.format(new Date(e.getRefreshTime())));
    }
  }

  @Test
  public void testProcessingStateInitial() {
    Entry e = new Entry();
    assertEquals(Entry.ProcessingState.DONE, e.getProcessingState());
  }

  @Test
  public void testProcessingFetch() {
    Entry e = new Entry();
    synchronized (e) {
      e.setNextRefreshTime(4711);
      e.startProcessing(Entry.ProcessingState.REFRESH, null);
      assertTrue(e.isGettingRefresh());
      e.processingDone();
    }
    assertEquals(Entry.ProcessingState.DONE, e.getProcessingState());
  }

  @Test
  public void testHot() {
    Entry e = new Entry();
    assertFalse(e.isHot());
    e.setHot(true);
    assertTrue(e.isHot());
    e.setHot(false);
    assertFalse(e.isHot());
  }

  /**
   * Just check that toString is producing no exception on empty entry.
   */
  @Test
  public void virginToString() {
    Entry e = new Entry();
    e.toString();
  }

  @Test
  public void timeSpan32Bit() {
    int _days = Integer.MAX_VALUE
      / 1000
      / 60
      / 60
      / 24;
    assertEquals(24, _days);
  }

  @Test
  public void getValue() {
    new Entry().getValue();
  }

  @Test
  public void num2processingState() {
    assertEquals("DONE", Entry.num2processingStateText(Entry.ProcessingState.DONE));
    assertEquals("READ", Entry.num2processingStateText(Entry.ProcessingState.READ));
    assertEquals("READ_COMPLETE", Entry.num2processingStateText(Entry.ProcessingState.READ_COMPLETE));
    assertEquals("MUTATE", Entry.num2processingStateText(Entry.ProcessingState.MUTATE));
    assertEquals("LOAD", Entry.num2processingStateText(Entry.ProcessingState.LOAD));
    assertEquals("COMPUTE", Entry.num2processingStateText(Entry.ProcessingState.COMPUTE));
    assertEquals("REFRESH", Entry.num2processingStateText(Entry.ProcessingState.REFRESH));
    assertEquals("EXPIRY", Entry.num2processingStateText(Entry.ProcessingState.EXPIRY));
    assertEquals("EXPIRY_COMPLETE", Entry.num2processingStateText(Entry.ProcessingState.EXPIRY_COMPLETE));
    assertEquals("WRITE", Entry.num2processingStateText(Entry.ProcessingState.WRITE));
    assertEquals("WRITE_COMPLETE", Entry.num2processingStateText(Entry.ProcessingState.WRITE_COMPLETE));
    assertEquals("STORE", Entry.num2processingStateText(Entry.ProcessingState.STORE));
    assertEquals("STORE_COMPLETE", Entry.num2processingStateText(Entry.ProcessingState.STORE_COMPLETE));
    assertEquals("NOTIFY", Entry.num2processingStateText(Entry.ProcessingState.NOTIFY));
    assertEquals("PINNED", Entry.num2processingStateText(Entry.ProcessingState.PINNED));
    assertEquals("LAST", Entry.num2processingStateText(Entry.ProcessingState.LAST));
  }

}
