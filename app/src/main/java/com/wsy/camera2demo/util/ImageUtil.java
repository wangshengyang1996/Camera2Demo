package com.wsy.camera2demo.util;

public class ImageUtil {
    /**
     * 将Y:U:V == 4:2:2的数据转换为nv21
     *
     * @param y    Y 数据
     * @param u    U 数据
     * @param v    V 数据
     * @param nv21 生成的nv21，需要预先分配内存
     */
    public static void yuv422ToYuv420sp(byte[] y, byte[] u, byte[] v, byte[] nv21) {
        System.arraycopy(y, 0, nv21, 0, y.length);
        int nv21UVIndex = y.length;
        int length = y.length + u.length / 2 + v.length / 2;
        int uIndex = 0, vIndex = 0;
        for (int i = nv21UVIndex; i < length; i += 2) {
            vIndex += 2;
            uIndex += 2;
            nv21[i] = v[vIndex];
            nv21[i + 1] = u[uIndex];
        }
    }
}
