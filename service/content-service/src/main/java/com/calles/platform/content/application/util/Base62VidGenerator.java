package com.calles.platform.content.application.util;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 业务对外公开短码 vid 发号器。
 *
 * <p>核心规范与设计原由：
 * <ul>
 *   <li><b>固定前缀</b>：{@code cv} (Content Video)；</li>
 *   <li><b>编码算法</b>：将 128 位高熵 UUID (BigInteger 正整数) 压缩为 62 进制字母表字符集 (0-9, A-Z, a-z)；</li>
 *   <li><b>固定长度</b>：2 位前缀 + 22 位 Base62 字符 = 24 位统一长度；</li>
 *   <li><b>安全性与易用性</b>：离散高熵无序、彻底杜绝自增 ID 带来的爬虫遍历与商业数据探测隐患，同时天然具备 URL 友好性。</li>
 * </ul>
 * </p>
 */
public final class Base62VidGenerator {

    /** 视频公开编码固定前缀。 */
    public static final String PREFIX = "cv";

    /** Base62 标准字符表 (0-9, A-Z, a-z)。 */
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** 进制基数 62。 */
    private static final BigInteger BASE = BigInteger.valueOf(62);

    /** Base62 编码固定长度 (128位正整数在 62 进制下的上限长度为 22)。 */
    private static final int ENCODED_LENGTH = 22;

    /**
     * 工具类私有构造函数，禁止外部实例化。
     */
    private Base62VidGenerator() {
    }

    /**
     * 生成全新的 24 位高熵业务短码 vid。
     *
     * @return 格式为 "cv" + 22位Base62字符的字符串 (如 "cv05hG9Kq2RtLw7XbPmZv4Ya")
     */
    public static String generateVid() {
        return generateVid(UUID.randomUUID());
    }

    /**
     * 根据指定的 UUID 编码生成确定性的 24 位业务编码 vid。
     *
     * @param uuid 输入的唯一 UUID 实例
     * @return 24 位全局唯一业务公开短码
     * @throws IllegalArgumentException 当传入的 uuid 为 null 时抛出
     */
    public static String generateVid(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("UUID 不能为空");
        }
        // 步骤 1：将 UUID 的高低 64 位拼接为 16 字节无符号大正整数
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        BigInteger number = new BigInteger(1, buffer.array());

        // 步骤 2：对 62 取模实施连续除法，提取字符索引
        StringBuilder sb = new StringBuilder();
        while (number.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = number.divideAndRemainder(BASE);
            sb.append(ALPHABET[divRem[1].intValue()]);
            number = divRem[0];
        }

        // 步骤 3：高位补齐 '0'，确保固定 22 位长度，避免长度抖动影响前端布局
        while (sb.length() < ENCODED_LENGTH) {
            sb.append('0');
        }

        // 步骤 4：翻转结果（除留余数法逆序拼接）并拼接业务前缀
        return PREFIX + sb.reverse();
    }
}
