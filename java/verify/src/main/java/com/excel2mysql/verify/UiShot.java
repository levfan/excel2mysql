package com.excel2mysql.verify;

import com.excel2mysql.core.TableOps;
import com.excel2mysql.gui.AboutDialog;
import com.excel2mysql.gui.DDLDialog;
import com.excel2mysql.gui.HelpDialog;
import com.excel2mysql.gui.MainFrame;
import com.excel2mysql.gui.ProgressDialog;
import com.excel2mysql.gui.StructureDialog;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 Java Swing 这条线的界面渲染成 PNG，与 Python 侧同名快照逐屏比对。
 *
 * 锁屏或无显示设备上截屏只能拿到锁屏图，所以自己画：定好尺寸、走一遍布局，再 paint 到位图上。
 * 渲染的是线上那套控件，不是照着截图画的仿品；屏序与 py_shot.py 一一对应，同名才能逐张对。
 *
 * 每一步都从主线程用 invokeAndWait 交给事件分发线程：控件更新是排进队列的，
 * 步骤之间回到主线程就等于过了一次事件屏障，拍到的才是用户真会看到的那一帧。
 */
public final class UiShot {

    private static final int W = 1100;
    private static final int H = 780;

    public static void main(String[] args) throws Exception {
        ui(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                System.out.println("! [WARN] UI-SHOT | 系统外观设置失败：" + e.getMessage());
            }
        });
        MainFrame.modalProgress = false;
        final String dir = args.length > 0 ? args[0]
                : System.getenv("SHOT_OUT") == null ? "shots/java" : System.getenv("SHOT_OUT");
        File fix = new File(args.length > 1 ? args[1] : "verify/fixtures");
        final List<String> files = new ArrayList<String>();
        File[] kids = fix.listFiles();
        if (kids != null) {
            java.util.Arrays.sort(kids);
            for (File f : kids) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".zip")) {
                    files.add(f.getPath());
                }
            }
        }
        // 驱动留在主线程：每步单独派发给事件分发线程，回来时队列已经排空。
        // 整段包一次 invokeAndWait 的话，段内再想等一轮事件就是自己等自己。
        try {
            shoot(dir, files);
        } catch (Throwable t) {
            System.out.println("x [ERROR] UI-SHOT | 渲染失败：" + t);
            t.printStackTrace();
            System.exit(1);
        }
        System.out.println("+ [OK] UI-SHOT | 输出目录 " + new File(dir).getAbsolutePath());
        System.exit(0);
    }

    /** 交给事件分发线程执行并等它做完。绝不能站在事件分发线程上自己调。 */
    private static void ui(Runnable onEdt) throws Exception {
        SwingUtilities.invokeAndWait(onEdt);
    }

    private static void shoot(String dir, List<String> files) throws Exception {
        final MainFrame[] box = new MainFrame[1];
        ui(() -> box[0] = new MainFrame());
        final MainFrame frame = box[0];
        // 画客户区而不是整窗：Qt 的 grab() 本来就不含标题栏，画整窗会把底部挤到画面外
        ui(() -> {
            frame.getContentPane().setPreferredSize(new Dimension(W, H));
            frame.pack();
            frame.setVisible(true);
        });
        snapClient(frame, dir, "01_empty");

        ui(() -> frame.addFiles(files));
        snapClient(frame, dir, "02_excel_loaded");

        ui(() -> frame.selectTab(1));
        snapClient(frame, dir, "03_sql_empty");

        File sql = new File(dir, "shot_a.sql");
        writeSql(sql);
        final List<String> paths = new ArrayList<String>();
        paths.add(sql.getPath());
        ui(() -> {
            frame.sqlPage().setPrefixText("ods_");
            frame.sqlPage().addFiles(paths);
        });
        snapClient(frame, dir, "04_sql_loaded");

        ui(() -> frame.sqlPage().applyPrefix());
        snapClient(frame, dir, "05_sql_prefixed");

        final List<TableOps.Table> two = new ArrayList<TableOps.Table>();
        ui(() -> {
            for (int i = 0; i < Math.min(2, frame.tables().size()); i++) {
                two.add(frame.tables().get(i));
            }
        });
        snapDialog(new StructureDialog(frame, frame.results(), frame.logPanel()),
                dir, "06_structure", 980, 640);
        snapDialog(new DDLDialog(frame, two, false), dir, "07_ddl", 900, 600);
        snapDialog(new HelpDialog(frame), dir, "08_help", 960, 700);
        snapDialog(new AboutDialog(frame), dir, "09_about", 380, 168);

        final ProgressDialog[] pdBox = new ProgressDialog[1];
        ui(() -> {
            ProgressDialog pd = new ProgressDialog(frame, "正在解析文件…");
            pd.setTotal(12);
            pd.advance(5, "订单表.xlsx");
            pdBox[0] = pd;
        });
        snapDialog(pdBox[0], dir, "10_progress", 480, 140);

        ui(() -> frame.dispose());
        sql.delete();
    }

    /** 布局、等一轮事件、按客户区实际尺寸画：分开做才拿得到最终那一帧。 */
    private static void snapClient(final javax.swing.RootPaneContainer w,
                                   final String dir, final String name) throws Exception {
        final java.awt.Component host = (java.awt.Component) w;
        ui(host::validate);
        final Container pane = w.getContentPane();
        final int[] box = new int[2];
        ui(() -> {
            box[0] = pane.getWidth();
            box[1] = pane.getHeight();
        });
        ui(() -> {
            try {
                snap(pane, dir, name, box[0], box[1]);
            } catch (Exception e) {
                throw new IllegalStateException("画 " + name + " 失败", e);
            }
        });
    }

    /**
     * 弹窗要先真的显示出来才有可绘制的对等体，直接 paint 会得到一张白图。
     * 构造时是模态的那几个临时改成非模态，否则 setVisible 会把探针挂死。
     */
    private static void snapDialog(final javax.swing.JDialog dialog, String dir, String name,
                                   final int w, final int h) throws Exception {
        ui(() -> {
            dialog.setModal(false);
            dialog.getContentPane().setPreferredSize(new Dimension(w, h));
            dialog.pack();
            dialog.setVisible(true);
            dialog.validate();
        });
        snapClient((javax.swing.RootPaneContainer) dialog, dir, name);
        ui(() -> dialog.dispose());
    }

    private static void writeSql(File sql) throws Exception {
        String text = "CREATE TABLE t_order (id INT);\r\n"
                + "INSERT INTO `t_order` VALUES (1);\r\n";
        java.nio.file.Files.write(sql.toPath(), text.getBytes("UTF-8"));
    }

    /** 把组件画成 PNG：先铺白底，否则透明区在比对时看不出是布局问题还是底色问题。 */
    private static void snap(Component c, String dir, String name, int w, int h)
            throws Exception {
        new File(dir).mkdirs();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        c.paint(g);
        g.dispose();
        File f = new File(dir, name + ".png");
        ImageIO.write(img, "png", f);
        System.out.println("saved " + f.getAbsolutePath() + " " + w + " x " + h);
    }

    private UiShot() {
    }
}
