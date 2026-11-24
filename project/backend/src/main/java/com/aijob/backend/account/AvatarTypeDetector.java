package com.aijob.backend.account;

/**
 * 图片真实类型嗅探：只信任魔数，不信任扩展名与请求声明的 Content-Type。
 */
public final class AvatarTypeDetector {

    private AvatarTypeDetector() {
    }

    public static String detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if (isJpeg(bytes)) {
            return "image/jpeg";
        }
        if (isPng(bytes)) {
            return "image/png";
        }
        if (isWebp(bytes)) {
            return "image/webp";
        }
        return null;
    }

    /**
     * JPEG/PNG 校验可解码性；WebP 仅校验容器结构（不引入解码库）。
     */
    public static boolean decodable(byte[] bytes, String mimeType) {
        if ("image/webp".equals(mimeType)) {
            return isWebp(bytes);
        }
        try {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}