package com.liferecorder.widget

/**
 * 세로 1x2 위젯. 동작은 [RecordWidget]과 완전히 같고 배치만 세로다.
 * 위젯 고르는 화면에 두 크기를 따로 내보이려면 provider가 둘이어야 한다.
 */
class RecordWidgetTall : RecordWidget() {
    override val tall: Boolean get() = true
}
