---
name: render-temp-horse-via-dispatcher
description: 马车手画临时Horse改用EntityRenderDispatcher.render让EMF/FreshAnimations捕获;1.20.1实测的yaw/scale/walkAnimation/阴影约定
metadata:
  type: project
---

CarriageEntityRenderer.renderAttachedHorse 在车头前手画临时 Horse(不入世)。装 Fresh Animations 资源包+EMF mod 时马只播待机:因为直接调 horseModel.setupAnim 绕过了 EntityRenderDispatcher.render,EMF 的 HEAD mixin(setCurrentEntityIteration) 没捕获到这匹马。修法=改用 `Minecraft.getInstance().getEntityRenderDispatcher().render(horse, 0,0,0, 0f, partialTick, poseStack, buffer, light)`。

**Why:** EMF/FA 从 entity.walkAnimation.speed()/position() 读腿摆,且其上下文捕获 mixin 只在 EntityRenderDispatcher.render 触发。手动 renderToBuffer 路径不经过它。

**How to apply (1.20.1 实测签名,来自 loom-mapped jar+ForgeFlower 反编译,已核准):**
- `EntityRenderDispatcher.render(E entity, double x,y,z, float rotationYaw, float partialTick, PoseStack, MultiBufferSource, int light)`。内部仅 `poseStack.translate(x,y,z)`(getRenderOffset 对 Horse=Vec3.ZERO)。GeoEntityRenderer 调进来的 poseStack 已在相机相对实体原点,所以 **x=y=z=0**,别传世界坐标。
- **朝向靠 yBodyRot 不靠 rotationYaw 参数**:LivingEntityRenderer.render 用 `h=rotLerp(yBodyRotO,yBodyRot)`,setupRotations 内部 `mulPose(YP, 180-h)`。所以喂 `horse.setYBodyRot(yaw); yBodyRotO=yaw; setYHeadRot(yaw); yHeadRotO=yaw`,rotationYaw 参数传 0f,**删掉手动的 mulPose(YP,180-yaw)**(否则双重 yaw)。
- **scale 内部翻转**:LivingEntityRenderer 内部 `scale(-1,-1,1)`。外层只给**正值** `poseStack.scale(2.2,2.2,2.2)`,**去掉负号**(原手动 renderToBuffer 时写的 scale(-2.2,-2.2,2.2) 负号是替它翻转;改 dispatcher 后留负号会双重翻转→马上下颠倒)。
- **walkAnimation 双硬门槛**:LivingEntityRenderer 仅当 `!isPassenger() && isAlive()` 才读 walkAnimation.speed(g)/position(g)。临时 Horse 别设 vehicle、别 setRemoved/掉血。`walkAnimation.update(speed,1.0F)` 一次喂值同时驱动 vanilla 和 EMF,内部自己推进 position 相位,不用手动设 tickCount/prev。
- **关阴影用 `dispatcher.setRenderShadow(false)` 包 try/finally 还原 true**(全局开关,无 getter);**别用 setInvisible(true)**(会触发半透明 0.15alpha 渲染路径)。名字标签默认不渲染(裸 new Horse 无 hasCustomName)。
- **坑:内部 `translate(0,-1.501,0)`**(脚→中心原点约定),改 dispatcher 后马 Y 落点变,CarriageVisualRig.HORSE_MODEL_Y(原3.0) 几乎肯定要游戏内重调。
- isAddedToWorld=false 不影响 dispatcher.render,不 NPE。单匹复用 renderHorse 实例稳定即可,getId()=0 单匹无碰撞,无需设 id/uuid。
