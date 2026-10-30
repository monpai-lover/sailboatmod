# Physical logistics motion safety contracts implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 固定海陆空共用的停车距离、曲率速度和停泊观测契约，为后续接入真实载具准备可以运行的测试与纯模型。

**Architecture:** 新增不依赖 Minecraft/Sable 的运动安全策略。它只计算约束、检查观测，不输出动力，不操作物品、不宣告订单交付。M0b 才从真实适配器生成观测并把结果接入控制器和设施；M0c 再将停泊许可关联实际装卸事务。

**Tech Stack:** Java 21、JUnit 5.10.2、现有 Gradle test；不新增依赖。

**Status:** 未执行的 M0a 计划，基线 `edfa16de`。本次只提交文档。全部后续行为和验收见 [详细设计](../specs/2026-10-30-physical-logistics-design.md)；这里不声称一个纯策略类已经实现三介质物流。

---

## 范围和文件

工作目录：`/Users/goatdie/sailboatmod/.worktrees/1.21.1-neoforge`。继续使用当前隔离 worktree；先检查状态并保留已有改动。

只创建：

- `src/main/java/com/monpai/sailboatmod/transport/physical/safety/MotionSafetyPolicy.java`
- `src/test/java/com/monpai/sailboatmod/transport/physical/safety/MotionSafetyPolicyTest.java`

验收后仅更新本计划的实际完成勾选和验证命令结果。M0a 不修改网络协议、包版本、登记 BE、实际导航或旧实体船/车。刻意将纯契约与运行时接入分成两批，避免在角速度、接地、控制权尚未观测到时用假值启用门禁。

## Task 1：先写失败用例

- [ ] 检查 `git status --short` 和 `git rev-parse HEAD`，确认没有覆盖其他人的修改。
- [ ] 创建以下完整测试文件。测试使用候选默认值：稳定 40 tick、采样间隔和年龄最多 1 tick、线速度 0.25 格/秒、角速度 2 度/秒、朝向误差 5 度。

```java
package com.monpai.sailboatmod.transport.physical.safety;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.physical.safety.MotionSafetyPolicy.*;

class MotionSafetyPolicyTest {
    private static final UUID MISSION = new UUID(1, 1);
    private static final UUID BODY = new UUID(2, 2);
    private static final Limits LIMITS = new Limits(0.25, 2, 5, 40, 1, 1);
    private static final Context CONTEXT = new Context(MISSION, 1, BODY,
            "minecraft:overworld/airport-a/pad-1", 7, 1);

    private static Sample sample(Context context, long tick, double speed, double angular,
                                 double heading, boolean inside, boolean secured,
                                 boolean clearance, boolean cargo, boolean control, boolean permit) {
        return new Sample(context, tick, speed, angular, heading,
                inside, secured, clearance, cargo, control, permit);
    }
    private static Sample parked(long tick) {
        return sample(CONTEXT, tick, 0, 0, 0, true, true, true, true, true, true);
    }
    private static Window settled() {
        Window state = null;
        for (int tick = 0; tick <= 40; tick++) {
            state = evaluate(state, parked(tick), LIMITS, tick).window();
        }
        return state;
    }

    @Test void stoppingDistanceIncludesReactionBrakingAndMargin() {
        assertEquals(26, stoppingDistance(8, 2, 1, 2).orElseThrow(), 1e-9);
        assertEquals(0.5, stoppingDistance(1e154, 1e308, 0, 0).orElseThrow(), 1e-9);
        assertTrue(stoppingDistance(8, 0, 1, 2).isEmpty());
        assertTrue(stoppingDistance(Double.NaN, 2, 1, 2).isEmpty());
        assertTrue(stoppingDistance(Double.MAX_VALUE, 1, 1, 1).isEmpty());
    }
    @Test void curveLimitRespectsVehicleCapabilityAndBothTurnDirections() {
        assertEquals(4, curveSpeedLimit(0.125, 2, 10).orElseThrow(), 1e-9);
        assertEquals(4, curveSpeedLimit(-0.125, 2, 10).orElseThrow(), 1e-9);
        assertEquals(10, curveSpeedLimit(0, 2, 10).orElseThrow(), 1e-9);
        assertEquals(3, curveSpeedLimit(0.125, 2, 3).orElseThrow(), 1e-9);
        assertTrue(curveSpeedLimit(0.1, 0, 10).isEmpty());
    }
    @Test void requiresFortyTicksOfFreshStableObservations() {
        Window state = null;
        for (int tick = 0; tick <= 40; tick++) {
            Decision decision = evaluate(state, parked(tick), LIMITS, tick);
            assertEquals(tick == 40, decision.ready());
            state = decision.window();
        }
    }
    @Test void verticalOrHorizontalMotionUsesFullThreeDimensionalSpeed() {
        Decision falling = evaluate(settled(), sample(CONTEXT, 41, 0.26, 0, 0,
                true, true, true, true, true, true), LIMITS, 41);
        assertEquals(Reason.MOVING, falling.reason());
        assertNull(falling.window());
        assertFalse(falling.ready());
    }
    @Test void rotationAndHeadingBlockTransfer() {
        assertEquals(Reason.ROTATING, evaluate(settled(), sample(CONTEXT, 41, 0, 2.01, 0,
                true, true, true, true, true, true), LIMITS, 41).reason());
        assertEquals(Reason.MISALIGNED, evaluate(settled(), sample(CONTEXT, 41, 0, 0, -5.01,
                true, true, true, true, true, true), LIMITS, 41).reason());
    }
    @Test void geometrySupportCargoControlAndPermitAreAllRequired() {
        for (int missing = 0; missing < 6; missing++) {
            boolean[] flags = {true, true, true, true, true, true};
            flags[missing] = false;
            Decision result = evaluate(settled(), sample(CONTEXT, 41, 0, 0, 0,
                    flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]), LIMITS, 41);
            assertFalse(result.ready(), "missing gate " + missing);
            assertEquals(Reason.GATE_UNPROVEN, result.reason());
            assertNull(result.window());
        }
    }
    @Test void staleAndFutureSamplesNeverEstablishParking() {
        assertEquals(Reason.STALE, evaluate(settled(), parked(41), LIMITS, 43).reason());
        assertEquals(Reason.STALE, evaluate(settled(), parked(42), LIMITS, 41).reason());
    }
    @Test void duplicateAndReorderedSamplesDoNotAccumulateTime() {
        Window state = settled();
        assertEquals(Reason.REPLAYED, evaluate(state, parked(40), LIMITS, 40).reason());
        assertEquals(Reason.REPLAYED, evaluate(state, parked(39), LIMITS, 40).reason());
        assertFalse(evaluate(state, parked(40), LIMITS, 40).ready());
    }
    @Test void samplingGapRestartsTheWindow() {
        Decision result = evaluate(settled(), parked(42), LIMITS, 42);
        assertEquals(Reason.WAIT_STABLE, result.reason());
        assertEquals(42, result.window().firstTick());
        assertFalse(result.ready());
    }
    @Test void missionBodyFacilityOrGenerationChangeInvalidatesOldEvidence() {
        Context[] changed = {
            new Context(new UUID(3, 3), 1, BODY, CONTEXT.facilityKey(), 7, 1),
            new Context(MISSION, 2, BODY, CONTEXT.facilityKey(), 7, 1),
            new Context(MISSION, 1, new UUID(4, 4), CONTEXT.facilityKey(), 7, 1),
            new Context(MISSION, 1, BODY, "minecraft:overworld/airport-a/pad-2", 7, 1),
            new Context(MISSION, 1, BODY, CONTEXT.facilityKey(), 8, 1),
            new Context(MISSION, 1, BODY, CONTEXT.facilityKey(), 7, 2)
        };
        for (Context context : changed) {
            Decision result = evaluate(settled(), sample(context, 41, 0, 0, 0,
                    true, true, true, true, true, true), LIMITS, 41);
            assertFalse(result.ready());
            assertEquals(41, result.window().firstTick());
        }
    }
    @Test void movingThenStoppingNeedsANewFullWindow() {
        Decision moving = evaluate(settled(), sample(CONTEXT, 41, 1, 0, 0,
                true, true, true, true, true, true), LIMITS, 41);
        Window state = moving.window();
        for (int tick = 42; tick <= 82; tick++) {
            Decision decision = evaluate(state, parked(tick), LIMITS, tick);
            assertEquals(tick == 82, decision.ready());
            state = decision.window();
        }
    }
    @Test void tighteningLimitsCannotReusePreviouslyLooserEvidence() {
        Window state = null;
        for (int tick = 0; tick <= 40; tick++) {
            state = evaluate(state, sample(CONTEXT, tick, 0.2, 0, 0,
                    true, true, true, true, true, true), LIMITS, tick).window();
        }
        Limits stricter = new Limits(0.1, 2, 5, 40, 1, 1);
        Decision result = evaluate(state, parked(41), stricter, 41);
        assertFalse(result.ready());
        assertEquals(41, result.window().firstTick());
    }
    @Test void invalidNumbersAndLimitsFailClosed() {
        assertEquals(Reason.INVALID, evaluate(null, sample(CONTEXT, 0, Double.NaN, 0, 0,
                true, true, true, true, true, true), LIMITS, 0).reason());
        assertEquals(Reason.INVALID, evaluate(null, sample(CONTEXT, 0, 0, -1, 0,
                true, true, true, true, true, true), LIMITS, 0).reason());
        assertEquals(Reason.INVALID, evaluate(null, parked(0),
                new Limits(0.25, 2, 5, 40, 1, 0), 0).reason());
        assertEquals(Reason.INVALID, evaluate(null, null, LIMITS, 0).reason());
        assertEquals(Reason.INVALID, evaluate(new Window(null, LIMITS, 0, 40),
                parked(41), LIMITS, 41).reason());
    }
}
```

- [ ] 运行 `./gradlew test --tests 'com.monpai.sailboatmod.transport.physical.safety.MotionSafetyPolicyTest' --no-daemon --console=plain`。
- [ ] 预期先因 `MotionSafetyPolicy` 不存在而编译失败，记录 RED 证据。若已存在同名类，先比较行为与测试，不覆盖其他实现。

## Task 2：实现完整纯策略

- [ ] 创建以下完整实现。输入 speed/angularSpeed 是已转换单位后的**非负模长**；headingError 是已规范到 [-180, 180] 的有符号误差。Sample 的六个证据位只能由真实适配器给出，UNKNOWN 转 false。

```java
package com.monpai.sailboatmod.transport.physical.safety;

import java.util.OptionalDouble;
import java.util.UUID;

/** Planning and observation only; never supplies propulsion or authorizes a cargo commit. */
public final class MotionSafetyPolicy {
    private MotionSafetyPolicy() { }

    public record Context(UUID missionId, long generation, UUID physicalId,
                          String facilityKey, long facilityRevision, long evidenceRevision) { }
    public record Limits(double maxSpeed, double maxAngularSpeed, double maxHeadingError,
                         long stableTicks, long maxAgeTicks, long maxGapTicks) { }
    public record Sample(Context context, long tick, double speed, double angularSpeed,
                         double headingError, boolean insideEnvelope, boolean supportedOrSecured,
                         boolean clearanceValid, boolean cargoBindingValid,
                         boolean controlAuthorized, boolean facilityPermitValid) { }
    public record Window(Context context, Limits limits, long firstTick, long lastTick) { }
    public enum Reason { INVALID, STALE, REPLAYED, MOVING, ROTATING, MISALIGNED,
                         GATE_UNPROVEN, WAIT_STABLE, READY }
    public record Decision(Window window, boolean ready, Reason reason) { }

    public static OptionalDouble stoppingDistance(double speed, double deceleration,
                                                   double reactionSeconds, double margin) {
        if (!nonnegative(speed) || !positive(deceleration)
                || !nonnegative(reactionSeconds) || !nonnegative(margin)) return OptionalDouble.empty();
        double distance = speed * reactionSeconds + (speed / deceleration) * (speed * 0.5) + margin;
        return Double.isFinite(distance) ? OptionalDouble.of(distance) : OptionalDouble.empty();
    }

    public static OptionalDouble curveSpeedLimit(double curvature, double lateralAcceleration,
                                                  double vehicleSpeedLimit) {
        if (!Double.isFinite(curvature) || !positive(lateralAcceleration)
                || !positive(vehicleSpeedLimit)) return OptionalDouble.empty();
        if (curvature == 0) return OptionalDouble.of(vehicleSpeedLimit);
        double value = Math.min(vehicleSpeedLimit,
                Math.sqrt(lateralAcceleration / Math.abs(curvature)));
        return positive(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    public static Decision evaluate(Window previous, Sample sample, Limits limits, long now) {
        if (!valid(sample, limits) || now < 0) return reject(Reason.INVALID);
        if (previous != null && (previous.context() == null || previous.limits() == null
                || previous.firstTick() < 0 || previous.lastTick() < previous.firstTick()))
            return reject(Reason.INVALID);
        if (sample.tick() > now || now - sample.tick() > limits.maxAgeTicks()) return reject(Reason.STALE);
        if (previous != null && previous.context().equals(sample.context())) {
            if (sample.tick() <= previous.lastTick()) return reject(Reason.REPLAYED);
        }
        if (sample.speed() > limits.maxSpeed()) return reject(Reason.MOVING);
        if (sample.angularSpeed() > limits.maxAngularSpeed()) return reject(Reason.ROTATING);
        if (Math.abs(sample.headingError()) > limits.maxHeadingError()) return reject(Reason.MISALIGNED);
        if (!sample.insideEnvelope() || !sample.supportedOrSecured() || !sample.clearanceValid()
                || !sample.cargoBindingValid() || !sample.controlAuthorized() || !sample.facilityPermitValid())
            return reject(Reason.GATE_UNPROVEN);

        long first = sample.tick();
        if (previous != null && previous.context().equals(sample.context())
                && previous.limits().equals(limits)
                && sample.tick() - previous.lastTick() <= limits.maxGapTicks()) first = previous.firstTick();
        Window next = new Window(sample.context(), limits, first, sample.tick());
        boolean ready = sample.tick() - first >= limits.stableTicks();
        return new Decision(next, ready, ready ? Reason.READY : Reason.WAIT_STABLE);
    }

    private static boolean valid(Sample sample, Limits limits) {
        if (sample == null || limits == null || sample.context() == null) return false;
        Context c = sample.context();
        return c.missionId() != null && c.physicalId() != null && c.generation() >= 0
                && c.facilityKey() != null && !c.facilityKey().isBlank() && c.facilityRevision() >= 0
                && c.evidenceRevision() >= 0
                && sample.tick() >= 0 && nonnegative(sample.speed()) && nonnegative(sample.angularSpeed())
                && Double.isFinite(sample.headingError()) && Math.abs(sample.headingError()) <= 180
                && nonnegative(limits.maxSpeed()) && nonnegative(limits.maxAngularSpeed())
                && nonnegative(limits.maxHeadingError()) && limits.maxHeadingError() <= 180
                && limits.stableTicks() > 0 && limits.maxAgeTicks() >= 0
                && limits.maxGapTicks() > 0 && limits.maxGapTicks() <= limits.stableTicks();
    }
    private static Decision reject(Reason reason) { return new Decision(null, false, reason); }
    private static boolean nonnegative(double value) { return Double.isFinite(value) && value >= 0; }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
```

- [ ] 运行 Task 1 中相同 Gradle 命令，预期 13 个用例通过。失败先检查逻辑，不放宽阈值或删除异常用例。
- [ ] 同时运行已有核心回归：

```sh
./gradlew test --tests '*MotionSafetyPolicyTest' --tests '*NativeNavigationDriverTest' --tests '*PhysicalTransportBoundaryTest' --tests '*AirportDomainTest' --no-daemon --console=plain
```

预期所有选中测试通过，旧驾驶器输入/输出保持不变。这些纯测试不证明真实载具已经停稳或在世界内运动过。

## Task 3：检查边界并交付下一批

- [ ] 检查新增代码没有可选模组 import、世界修改、反射、静态全局停车状态或库存操作。
- [ ] `Window` 只允许服务端从本类成功结果继续传递，不接受客户端传入，也不从旧存档恢复为 READY；换代次、重组、换设施、几何变化或阈值改变均重建稳定窗口。`evidenceRevision` 在校准档位/货舱绑定/控制租约改变时递增，由 M0b 的服务端观测协调器维护；不能仅让对应布尔位瞬间为 true 就复用旧证据。
- [ ] 通过单元测试后运行 `git diff --check`，提交仅这两个文件及实际验证记录。提交消息：`feat: define physical transport motion safety policies`。
- [ ] 记录 M0a 完成，继续 M0b 前核对以下输入契约。任何一项缺失都必须保留 UNKNOWN，不能通过新增布尔默认值伪造证据。

| M0b 必须提供 | 接入点 | 不成立时 |
|---|---|---|
| 真实 3D 速度、角速度、主世界姿态、sampleTick | `SableReflectionAccess` → `PhysicalObservation` | 新精确停车能力不可用，不能只取水平速度 |
| 每 tick 快速稳定观测；导航重规划可保持低频 | 注册 BE tick 与纯 evaluator | 仍每 5 tick 采样时不能将 maxGap=1 改大冒充连续观测 |
| 当前独立 missionId/generation/physicalId | 任务模型与控制租约 | 旧任务证据全部失效 |
| 单调 evidenceRevision、当前完整 Limits | 档案/货舱绑定/租约变更钩子 | 换档位或收紧阈值不得继承旧宽松窗口 |
| 实际分配的 facilityKey/revision | `AirportBlockEntity`、Dock/PostStation 的新设施服务 | 落到其他设施需显式改派，不能任意兼容设施直接交货 |
| 机体完整包络、姿态与准入体积 | mode-specific DockingVerifier | 仅 AABB 相交不足以证明停入 |
| 支承/系泊/保持证据、原生控制实际能力 | 模式适配器 | 仅收到 BRAKE=15 或 FC COMPLETED 不成立 |
| 货舱同结构、真实许可与净空版本 | Cargo / Facility 服务 | 禁止下一批转移，已提交货物转移进入核对而非重复执行 |

M0a 的 `ready` 仅指当前停泊观测符合策略。M0c 仍需同 tick 核查任务/设施租约、货物实际数量、目标容量和持久转移记录，才能开始一批传货。不能把 Decision 存盘后反复当作永久交付许可证。

## 本计划验证边界

本计划的代码块是待实施的明确起点；若在文档审查中单独编译/运行代码块，只说明候选模型通过其示例测试，不应勾选上述实施步骤。M0b/M0c/M1 以及三介质真机运输尚未实现，按详细设计拆成各自完整计划再执行。
