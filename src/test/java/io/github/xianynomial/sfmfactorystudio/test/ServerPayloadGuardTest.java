package io.github.xianynomial.sfmfactorystudio.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端网络入口的参数校验护栏。
 *
 * <p>背景：{@code SlotCalibrationBeginPayload} 的 {@code pos} 完全来自客户端。服务端拿到后
 * 会每 tick 对该坐标跑七向能力面扫描（{@code captureContents}），用来学习"视觉格 ↔ 真实槽位"。
 * 不做距离校验的话，任意坐标都能触发，等于给了客户端一个"远程探测任意容器内容何时变化"
 * 的接口，同时把扫描成本转嫁给服务端。
 *
 * <p>本测试同时固化一个<b>不能改错</b>的结论：这里<b>不能</b>用
 * {@code getBlockEntity(pos) instanceof Container} 来收窄。因为 {@code captureContents}
 * 走的是 SFM 的 {@code SFMBlockCapabilityDiscovery}，模组机器（Mekanism、AE2 等）的容器
 * 并不实现 vanilla {@code Container}——加上这个判断会把 SFM 最主要的适用场景全部误拒。
 */
public class ServerPayloadGuardTest {

    private static final Path CALIBRATION_MANAGER = Paths.get(
            "src/main/java/io/github/xianynomial/sfmfactorystudio/net/SlotCalibrationManager.java");

    /** begin() 方法头之后这么多行内必须出现距离校验（当前实现是第 2 行，留足余量）。 */
    private static final int METHOD_HEAD_WINDOW = 15;

    @Test
    public void calibrationBeginRejectsDistantPositions() throws IOException {
        List<String> lines = Files.readAllLines(CALIBRATION_MANAGER, StandardCharsets.UTF_8);
        int begin = indexOfBegin(lines);
        assertTrue(begin >= 0, "在 SlotCalibrationManager 里找不到 begin(ServerPlayer ...)");

        String head = codeHead(lines, begin);

        assertTrue(head.contains("distanceToSqr"),
                "SlotCalibrationManager.begin 必须在方法开头校验玩家与该坐标的距离，"
                        + "否则客户端可以指定任意远处坐标让服务端持续扫描（越权读取 + 服务端负载）");
        assertTrue(head.contains("MAX_DISTANCE_SQR"),
                "距离校验应使用 MAX_DISTANCE_SQR 常量，与 SlotCapabilityRequestHandler 保持同一语义");
    }

    @Test
    public void calibrationBeginDoesNotRequireVanillaContainer() throws IOException {
        List<String> lines = Files.readAllLines(CALIBRATION_MANAGER, StandardCharsets.UTF_8);
        int begin = indexOfBegin(lines);
        assertTrue(begin >= 0, "在 SlotCalibrationManager 里找不到 begin(ServerPlayer ...)");

        String head = codeHead(lines, begin);

        assertFalse(head.contains("instanceof Container"),
                "不要用 vanilla Container 收窄校准目标：captureContents 走 SFM 的 "
                        + "SFMBlockCapabilityDiscovery，模组机器（Mekanism/AE2 等）的容器不是 "
                        + "vanilla Container，加了会把主要使用场景全部误拒。");
    }

    /** 取 begin 方法头窗口内的代码文本，剥掉行注释——注释里提到关键字不应误判。 */
    private static String codeHead(List<String> lines, int begin) {
        int end = Math.min(lines.size(), begin + METHOD_HEAD_WINDOW);
        StringBuilder sb = new StringBuilder();
        for (int i = begin; i < end; i++) {
            sb.append(lines.get(i).replaceAll("//.*$", "")).append('\n');
        }
        return sb.toString();
    }

    private static int indexOfBegin(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("public static void begin(ServerPlayer")) return i;
        }
        return -1;
    }
}
