package com.veyra.gybeyond;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原生弹幕层：叠在 PlayerView 之上、手势层之下，不吃触摸。
 *
 * 不用属性动画，也不用一帧一帧改坐标：每条弹幕只记「剧内绝对时间轴 offsetMs」，
 * 绘制时用 (当前播放位置 - offsetMs) 直接算 x。好处是 seek / 倍速 / 暂停 / 切集
 * 都只要校准一次锚点，不会出现弹幕跟画面错位或越积越多。
 */
public final class DanmakuView extends View {
    private static final int TRACKS = 6;
    private static final int MAX_ON_SCREEN = 40;
    private static final int HOT_DIGG = 100;

    private static final class Entry {
        final HongguoDanmaku.Item item;
        final int track;
        final float width;
        Entry(HongguoDanmaku.Item item, int track, float width) {
            this.item = item; this.track = track; this.width = width;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> trackById = new HashMap<>();
    private final long[] trackReadyAt = new long[TRACKS];
    private Set<String> blocked = Collections.emptySet();

    private float textSize;
    private float lineHeight;
    private float topOffset;
    private int opacity = 88;
    private long travelMs = 9_000L;

    private boolean visible = true;
    private boolean playing;
    private float speed = 1f;
    private long anchorPosition;
    private long anchorWall;

    public DanmakuView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        textSize = 15 * density;
        lineHeight = textSize * 1.55f;
        topOffset = 80 * density;
        fill.setStyle(Paint.Style.FILL);
        fill.setTextSize(textSize);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2.4f * density);
        outline.setColor(0xCC000000);
        outline.setTextSize(textSize);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /** 字号 px、透明度 0-100、滚动全程毫秒、屏蔽词。由播放器设置面板调用。 */
    public void setStyle(float textSizePx, int opacityPercent, long travelMillis, Set<String> blockWords) {
        textSize = Math.max(10 * getResources().getDisplayMetrics().density, textSizePx);
        lineHeight = textSize * 1.55f;
        opacity = Math.max(20, Math.min(100, opacityPercent));
        travelMs = Math.max(4_000L, Math.min(20_000L, travelMillis));
        blocked = blockWords == null ? Collections.<String>emptySet() : new HashSet<>(blockWords);
        fill.setTextSize(textSize);
        outline.setTextSize(textSize);
        relayout(entries.isEmpty() ? Collections.<HongguoDanmaku.Item>emptyList() : currentItems());
        invalidate();
    }

    private List<HongguoDanmaku.Item> currentItems() {
        List<HongguoDanmaku.Item> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.item);
        return out;
    }

    /** 用「当前窗口 + 下一窗口」的合并列表整体替换；已在上屏的条目保留原轨道，避免闪跳。 */
    public void setItems(List<HongguoDanmaku.Item> items) {
        relayout(items == null ? Collections.<HongguoDanmaku.Item>emptyList() : items);
        invalidate();
    }

    private void relayout(List<HongguoDanmaku.Item> items) {
        List<HongguoDanmaku.Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> Long.compare(a.offsetMs, b.offsetMs));
        Map<String, Integer> previous = new HashMap<>(trackById);
        trackById.clear();
        Arrays.fill(trackReadyAt, Long.MIN_VALUE);
        entries.clear();
        float pxPerMs = getWidth() > 0 ? getWidth() / (float) travelMs : 0.15f;
        for (HongguoDanmaku.Item item : sorted) {
            if (item == null || item.text == null || item.text.isEmpty()) continue;
            boolean blockedText = false;
            for (String word : blocked) {
                if (!word.isEmpty() && item.text.contains(word)) { blockedText = true; break; }
            }
            if (blockedText) continue;
            float width = fill.measureText(item.text);
            Integer known = previous.get(item.id);
            int track;
            if (known != null && known >= 0 && known < TRACKS && item.offsetMs >= trackReadyAt[known]) {
                track = known;
            } else {
                track = -1;
                long earliest = Long.MAX_VALUE;
                for (int i = 0; i < TRACKS; i++) {
                    if (item.offsetMs >= trackReadyAt[i]) { track = i; break; }
                    if (trackReadyAt[i] < earliest) { earliest = trackReadyAt[i]; track = i; }
                }
                // 所有轨道都还被占着：这条直接丢弃。热门剧一个窗口上千条，本来就是采样。
                if (track < 0 || item.offsetMs < trackReadyAt[track]) continue;
            }
            trackById.put(item.id, track);
            trackReadyAt[track] = item.offsetMs + (long) ((width + 28 * getResources().getDisplayMetrics().density) / pxPerMs);
            entries.add(new Entry(item, track, width));
        }
    }

    /** 每 500ms 由播放器 tick 校准一次；帧间用墙钟插值，滚动才顺滑。 */
    public void setPosition(long positionMs, boolean isPlaying, float playbackSpeed) {
        float nextSpeed = Math.max(0.25f, Math.min(3f, playbackSpeed));
        if (!playing || Float.compare(speed, nextSpeed) != 0
                || Math.abs(positionMs - position()) > 260) {
            anchorPosition = positionMs;
            anchorWall = SystemClock.uptimeMillis();
        }
        if (isPlaying && !playing) anchorWall = SystemClock.uptimeMillis();
        playing = isPlaying;
        speed = nextSpeed;
        invalidate();
    }

    private long position() {
        return playing ? anchorPosition + (long) ((SystemClock.uptimeMillis() - anchorWall) * speed)
                : anchorPosition;
    }

    public void setOverlayVisible(boolean on) {
        visible = on;
        setVisibility(on ? VISIBLE : GONE);
        invalidate();
    }

    /** 换集 / seek / 关弹幕时清掉在屏内容和轨道占用。 */
    public void reset() {
        entries.clear();
        trackById.clear();
        Arrays.fill(trackReadyAt, Long.MIN_VALUE);
        playing = false;
        anchorPosition = 0;
        anchorWall = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) { return false; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible || entries.isEmpty() || getWidth() <= 0) return;
        long now = position();
        int widthPx = getWidth();
        int drawn = 0;
        int baseAlpha = Math.round(255 * opacity / 100f);
        for (Entry e : entries) {
            if (drawn >= MAX_ON_SCREEN) break;
            float elapsed = now - e.item.offsetMs;
            if (elapsed < 0 || elapsed > travelMs) continue;
            float x = widthPx - elapsed / travelMs * (widthPx + e.width);
            if (x > widthPx || x + e.width < 0) continue;
            float fade = elapsed < 260 ? elapsed / 260f
                    : (elapsed > travelMs - 420 ? (travelMs - elapsed) / 420f : 1f);
            int alpha = Math.round(baseAlpha * Math.max(0f, Math.min(1f, fade)));
            if (alpha <= 0) continue;
            boolean hot = e.item.digg >= HOT_DIGG;
            float y = topOffset + e.track * lineHeight + textSize;
            outline.setAlpha(alpha);
            fill.setAlpha(alpha);
            fill.setColor(hot ? 0xFFFFD45B : Color.WHITE);
            outline.setFakeBoldText(hot);
            fill.setFakeBoldText(hot);
            canvas.drawText(e.item.text, x, y, outline);
            canvas.drawText(e.item.text, x, y, fill);
            drawn++;
        }
        // 屏上没字就别空转 60fps；tick 每 500ms 的 invalidate 会把动画重新带起来
        if (playing && drawn > 0) postInvalidateOnAnimation();
    }
}
