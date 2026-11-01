package com.excel2mysql.gui;

/**
 * 「表名前缀」输入框的占位句：两页共用这一条（Python 侧同名常量在 main.py 的
 * {@code PREFIX_PLACEHOLDER}）。
 *
 * <p>两页做的是同一件事，措辞就该逐字相同——以前两页各写一句，同一颗按钮一个叫
 * "换掉已有首段"、一个叫"把 ods_t_order 换成 change_t_order"，用户没法判断是不是同一回事。
 * 页签之间真正的差异只有一条（哪一页对新添加的文件自动生效），那条写在两颗按钮的
 * tooltip 里，不塞进占位句。
 */
public final class PrefixHint {

    public static final String PLACEHOLDER =
            "可选，如 dim_：点「批量应用前缀」加上这段，"
            + "点「替换首个前缀」换掉第一个下划线前的内容";

    private PrefixHint() {
    }
}
