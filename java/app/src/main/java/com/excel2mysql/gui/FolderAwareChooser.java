package com.excel2mysql.gui;

import javax.swing.JFileChooser;
import java.io.File;

/**
 * 确认选择时若选中项是目录，就进入该目录并留在窗口里，绝不把目录当文件交出去。
 * 文件名框里粘带引号的路径（资源管理器「复制文件地址」的格式）时，JDK 8 的
 * BasicFileChooserUI 会跳过目录判断直接确认，这里补上最后一道。
 */
public final class FolderAwareChooser extends JFileChooser {

    @Override
    public void approveSelection() {
        File dir = firstDirectory(getSelectedFiles());
        if (dir == null && getSelectedFile() != null && getSelectedFile().isDirectory()) {
            dir = getSelectedFile();
        }
        if (dir == null) {
            super.approveSelection();
            return;
        }
        setSelectedFile(null);
        setSelectedFiles(null);
        setCurrentDirectory(dir);
    }

    private static File firstDirectory(File[] files) {
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f != null && f.isDirectory()) {
                return f;
            }
        }
        return null;
    }
}
