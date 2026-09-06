package com.liferecorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 프로세스와 수명을 같이하는 스코프. 서비스가 죽어도 끝내야 하는 작업(세그먼트 remux, 업로드 예약)에 쓴다.
 * 서비스 자체 스코프에 두면 stopAll → onDestroy → cancel 순서 때문에 마지막 세그먼트를 잃는다.
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
