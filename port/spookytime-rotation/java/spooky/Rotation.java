package spooky;

/** Иммутабельная пара yaw/pitch в градусах. pitch всегда в [-90, 90]. */
public final class Rotation {
    public final float yaw;
    public final float pitch;

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = MathX.clamp(pitch, -90.0F, 90.0F);
    }

    public Rotation addDegrees(double dYaw, double dPitch) {
        return new Rotation((float) (this.yaw + dYaw), (float) (this.pitch + dPitch));
    }

    /** Разница по yaw с учётом перехода через 180/-180. */
    public double deltaYawTo(Rotation other) {
        return MathX.wrapDegrees((double) other.yaw - (double) this.yaw);
    }

    public double deltaPitchTo(Rotation other) {
        return (double) other.pitch - (double) this.pitch;
    }

    public double distanceTo(Rotation other) {
        double dYaw = deltaYawTo(other);
        double dPitch = deltaPitchTo(other);
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    /** Округляет разницу до целого числа "пикселей" мыши (финальная квантование мода). */
    public Rotation snapToPixelGrid(Rotation target, double pixelStep) {
        if (pixelStep <= 0.0 || !Double.isFinite(pixelStep)) {
            return this;
        }
        double dYaw = MathX.wrapDegrees((double) target.yaw - (double) this.yaw);
        double dPitch = (double) target.pitch - (double) this.pitch;
        return new Rotation(
                (float) (this.yaw + Math.round(dYaw / pixelStep) * pixelStep),
                (float) (this.pitch + Math.round(dPitch / pixelStep) * pixelStep));
    }

    @Override
    public String toString() {
        return String.format("Rotation[yaw=%.4f, pitch=%.4f]", yaw, pitch);
    }
}
