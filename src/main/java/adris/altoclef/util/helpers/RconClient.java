package adris.altoclef.util.helpers;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 RCON 客户端 (Source RCON 协议, 与原版 server.properties 的 rcon 对应)。
 * 每条命令一个短连接: 各消费方 (隐形光源 / 玩家维度坐标 oracle) 触发频率都很低,
 * 短连接最简单且无陈旧连接问题。
 *
 * <p>(2026-10-09) 从 {@code InvisibleLightHelper} 的私有内部类提升为包级类,
 * 供 {@link RconPlayerOracle} 复用; 协议实现零改动。</p>
 *
 * <p>协议细节: 整数均为【小端】(Java DataInputStream 是大端, 必须手写 LE);
 * 包结构 = len(int32) + requestId(int32) + type(int32) + payload + 2 个 NUL。
 * 认证: type=3, 成功回包 requestId 同值 / 失败为 -1; 命令: type=2。</p>
 */
final class RconClient {
    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;

    private RconClient() {
    }

    static String sendCommand(String host, int port, String password, String command)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));

            writePacket(out, 7, TYPE_AUTH, password);
            Packet auth = readPacket(in);
            if (auth == null) throw new IOException("认证无响应");
            if (auth.requestId == -1) throw new IOException("认证被拒绝 (密码错误?)");

            writePacket(out, 8, TYPE_COMMAND, command);
            Packet resp = readPacket(in);
            if (resp == null) throw new IOException("命令无响应");
            return resp.payload;
        }
    }

    private static void writePacket(DataOutputStream out, int requestId, int type,
                                    String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        int len = 4 + 4 + body.length + 2;
        writeLeInt(out, len);
        writeLeInt(out, requestId);
        writeLeInt(out, type);
        out.write(body);
        out.write(0);
        out.write(0);
        out.flush();
    }

    private static Packet readPacket(DataInputStream in) throws IOException {
        int len = readLeInt(in);
        if (len < 10 || len > 4096) throw new IOException("非法包长度 " + len);
        int requestId = readLeInt(in);
        int type = readLeInt(in);
        int payloadLen = len - 10;
        byte[] body = new byte[payloadLen];
        in.readFully(body);
        in.readFully(new byte[2]); // 两个 NUL
        return new Packet(requestId, type, new String(body, StandardCharsets.UTF_8));
    }

    private static void writeLeInt(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static int readLeInt(DataInputStream in) throws IOException {
        int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException();
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private record Packet(int requestId, int type, String payload) {
    }
}
