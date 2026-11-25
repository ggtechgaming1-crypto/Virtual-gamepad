package com.example;

interface IInputService {
    void sendKeyEvent(int keyCode, int action) = 1;
    void sendJoystick(float x, float y, int axisX, int axisY) = 2;
    void sendTrigger(float value, int axis) = 3;
    void sendMultiKeyEvent(in int[] keyCodes, int action) = 4;
    void sendKeyboardEvent(int keyCode, int action) = 5;
    void sendMouseButton(int buttonState, int action) = 6;
    void destroy() = 16777114;
}
