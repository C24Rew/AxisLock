package com.c24rew.axislock;

public enum Axis {
    X, Y, Z;

    public Axis next() {
        return switch (this) {
            case X -> Y;
            case Y -> Z;
            case Z -> X;
        };
    }

    @Override
    public String toString() {
        return name(); // "X", "Y", or "Z"
    }
}