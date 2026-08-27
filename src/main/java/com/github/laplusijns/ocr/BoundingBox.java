package com.github.laplusijns.ocr;

public record BoundingBox(double left, double top, double right, double bottom) {
    public BoundingBox {
        if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)) {
            throw new IllegalArgumentException("Bounding box coordinates must be finite");
        }
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Invalid bounding box coordinate order");
        }
    }

    public double width() {
        return right - left;
    }

    public double height() {
        return bottom - top;
    }

    public double centerY() {
        return (top + bottom) / 2.0;
    }

    public BoundingBox union(final BoundingBox other) {
        return new BoundingBox(
                Math.min(left, other.left),
                Math.min(top, other.top),
                Math.max(right, other.right),
                Math.max(bottom, other.bottom));
    }
}
