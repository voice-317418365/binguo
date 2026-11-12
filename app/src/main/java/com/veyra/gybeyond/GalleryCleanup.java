package com.veyra.gybeyond;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「下载/Veyra」下被系统相册收录的视频从媒体库里摘掉，<b>但不删文件</b>。
 *
 * <p>为什么要绕这么大一圈：MediaStore 的删除是「连文件一起删」，没有「只删记录」这种接口。
 * 而本应用要保留视频文件（App 内还要播），所以只能：
 * <ol>
 *   <li>先把目标文件改名字挪进同目录下的隐藏暂存目录（rename 不动数据、也不需要媒体库）；</li>
 *   <li>再让系统按 URI 删那一批行 —— 此时原路径已经没有文件可删，MediaProvider 只会清掉数据库记录；</li>
 *   <li>最后把文件改回原名，恢复原路径。</li>
 * </ol>
 * 目录里已经有 Download/Veyra/.nomedia，所以改回来的文件不会被扫描器重新收录。
 *
 * <p>暂存清单落盘在 SharedPreferences 里：万一中途被杀进程/崩溃，下次启动
 * {@link #restore(Context)} 会把文件改回去，不会留下 .gallery-stage 里的孤儿文件。
 */
final class GalleryCleanup {
    private static final String PREFS = "gallery_cleanup_stage";
    private static final String KEY = "moves";
    private static final String STAGE_DIR = ".gallery-stage";
    private static final String TAG = "VeyraDownload";

    /** 一条待处理的媒体条目：uri 用来让系统删记录，file 用来先挪走再挪回。 */
    static final class Entry {
        final Uri uri;
        final File file;

        Entry(Uri uri, File file) {
            this.uri = uri;
            this.file = file;
        }
    }

    private GalleryCleanup() { }

    /** 查出 Download/Veyra 下所有已被媒体库收录的视频（需要读媒体权限，否则会是空表）。 */
    static List<Entry> find(Context context) {
        List<Entry> list = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.getContentUri("external");
        boolean scoped = Build.VERSION.SDK_INT >= 29;
        String selection = scoped
                ? MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
                : MediaStore.MediaColumns.DATA + " LIKE ?";
        String[] args = scoped
                ? new String[]{"Download/Veyra/%"}
                : new String[]{"%/Download/Veyra/%"};
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA};
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(collection, projection, selection, args, null)) {
            while (cursor != null && cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String path = cursor.getString(1);
                if (path == null || path.isEmpty()) continue;
                list.add(new Entry(ContentUris.withAppendedId(collection, id), new File(path)));
            }
        } catch (Exception error) {
            android.util.Log.w(TAG, "相册清理：查询媒体库失败", error);
        }
        return list;
    }

    /**
     * 把这一批文件挪进隐藏暂存目录，返回真正挪成功的条目（删记录只针对它们）。
     * 清单立刻落盘，崩溃后还能靠 {@link #restore(Context)} 找回来。
     */
    static List<Entry> stage(Context context, List<Entry> entries) {
        List<Entry> staged = new ArrayList<>();
        JSONArray moves = new JSONArray();
        for (Entry entry : entries) {
            File source = entry.file;
            if (source == null || !source.isFile()) continue;
            File dir = new File(source.getParentFile(), STAGE_DIR);
            if (!dir.exists() && !dir.mkdirs()) continue;
            // 加序号前缀：同一集目录下可能有同名文件（不同子目录已在各自暂存目录里，这里只防跨目录重名）
            File target = new File(dir, staged.size() + "_" + source.getName());
            if (!source.renameTo(target)) {
                android.util.Log.w(TAG, "相册清理：暂存失败 " + source);
                continue;
            }
            JSONArray pair = new JSONArray();
            pair.put(source.getAbsolutePath());
            pair.put(target.getAbsolutePath());
            moves.put(pair);
            staged.add(entry);
        }
        if (moves.length() > 0) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, moves.toString()).apply();
        }
        return staged;
    }

    /** 把暂存的文件改回原路径（正常收尾、用户取消、崩溃恢复都走这一个入口）。 */
    static void restore(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return;
        int back = 0;
        java.util.Set<File> stageDirs = new java.util.LinkedHashSet<>();
        try {
            JSONArray moves = new JSONArray(raw);
            for (int i = 0; i < moves.length(); i++) {
                JSONArray pair = moves.optJSONArray(i);
                if (pair == null || pair.length() < 2) continue;
                File source = new File(pair.getString(0));
                File target = new File(pair.getString(1));
                if (!target.isFile()) continue;               // 已被还原或丢失
                if (source.exists()) continue;                // 原位置又有文件了：不动，避免覆盖
                File parent = source.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (target.renameTo(source)) {
                    back++;
                    File stage = target.getParentFile();
                    if (stage != null) stageDirs.add(stage);
                } else {
                    android.util.Log.w(TAG, "相册清理：还原失败 " + target + " -> " + source);
                }
            }
        } catch (Exception error) {
            android.util.Log.w(TAG, "相册清理：还原清单解析失败", error);
        }
        // 文件都回去了再把空暂存目录删掉：边还原边删，删到中途会留下空目录（旧实现就是这样）。
        for (File dir : stageDirs) {
            File[] left = dir.listFiles();
            if (left == null || left.length == 0) dir.delete();
        }
        prefs.edit().remove(KEY).apply();
        android.util.Log.i(TAG, "相册清理：已还原 " + back + " 个文件");
    }
}
