package com.hiclone.whisperstt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hiclone.whisperstt.ui.theme.WhisperSTTTheme

class MainActivity : ComponentActivity() {

    private lateinit var whisperSTT: WhisperSTT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WhisperSTT 인스턴스 생성
        whisperSTT = WhisperSTT()

        setContent {
            WhisperSTTTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WhisperTestScreen(whisperSTT, this@MainActivity)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperSTT.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperTestScreen(whisperSTT: WhisperSTT, activity: MainActivity) {
    val context = LocalContext.current

    // 기존 상태들 유지
    var statusText by remember { mutableStateOf("Whisper STT 테스트 준비") }
    var transcriptionResult by remember { mutableStateOf("") }
    var isModelLoaded by remember { mutableStateOf(false) }

    // 새로 추가된 실시간 STT 상태들
    var isRealtimeSTTRunning by remember { mutableStateOf(false) }
    var realtimeResults by remember { mutableStateOf(listOf<String>()) }

    val listState = rememberLazyListState()

    // 권한 요청 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 권한 승인 시 실시간 STT 시작
            isRealtimeSTTRunning = whisperSTT.startRealtimeSTT(
                onResult = { result ->
                    if (result.trim().isNotEmpty()) {
                        realtimeResults = realtimeResults + "[${System.currentTimeMillis() % 100000}] $result"
                    }
                },
                onStatus = { status ->
                    statusText = status
                    isRealtimeSTTRunning = whisperSTT.isRealtimeSTTRunning()
                }
            )
        } else {
            statusText = "마이크 권한이 필요합니다"
        }
    }

    // 자동 스크롤
    LaunchedEffect(realtimeResults.size) {
        if (realtimeResults.isNotEmpty()) {
            listState.animateScrollToItem(realtimeResults.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "Whisper STT 테스트",
            style = MaterialTheme.typography.headlineMedium
        )

        // 기존 상태 카드 유지
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isModelLoaded)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "상태: $statusText",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "모델 로드됨: ${if (isModelLoaded) "예" else "아니오"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                // 실시간 STT 상태 추가
                Text(
                    text = "실시간 STT: ${if (isRealtimeSTTRunning) "실행 중" else "중지됨"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRealtimeSTTRunning)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 기존 모델 로드 버튼 유지
        Button(
            onClick = {
                statusText = "모델 로드 중..."
                // assets에서 tiny 모델 로드 (39MB)
                val result = whisperSTT.loadModelFromAssets(activity, "models/ggml-tiny.en.bin")
                isModelLoaded = result
                statusText = if (result) "tiny 모델 로드 성공!" else "모델 로드 실패 (파일 확인 필요)"
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRealtimeSTTRunning
        ) {
            Text("모델 로드 (tiny)")
        }

        // 기존 더미 오디오 테스트 버튼과 실시간 STT 버튼을 Row로 배치
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 기존 더미 오디오 테스트 버튼
            Button(
                onClick = {
                    statusText = "더미 오디오 테스트 중..."
                    transcriptionResult = ""

                    // 테스트용 더미 오디오 데이터 (1초, 16kHz)
                    val dummyAudio = FloatArray(16000) { (Math.sin(2 * Math.PI * 440 * it / 16000.0)).toFloat() }

                    // 백그라운드에서 음성 인식 실행
                    whisperSTT.transcribeAudio(dummyAudio) { result ->
                        // UI 스레드에서 결과 업데이트
                        activity.runOnUiThread {
                            transcriptionResult = result
                            statusText = "음성 인식 완료"
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = isModelLoaded && !isRealtimeSTTRunning
            ) {
                Text("더미 테스트")
            }

            // 새로 추가된 실시간 STT 버튼
            Button(
                onClick = {
                    if (isRealtimeSTTRunning) {
                        // STT 중지
                        whisperSTT.stopRealtimeSTT()
                        isRealtimeSTTRunning = false
                        statusText = "실시간 STT 중지됨"
                    } else {
                        // 마이크 권한 체크 후 STT 시작
                        when (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        )) {
                            PackageManager.PERMISSION_GRANTED -> {
                                isRealtimeSTTRunning = whisperSTT.startRealtimeSTT(
                                    onResult = { result ->
                                        if (result.trim().isNotEmpty()) {
                                            realtimeResults = realtimeResults + "[${System.currentTimeMillis() % 100000}] $result"
                                        }
                                    },
                                    onStatus = { status ->
                                        statusText = status
                                        isRealtimeSTTRunning = whisperSTT.isRealtimeSTTRunning()
                                    }
                                )
                            }
                            else -> {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = isModelLoaded,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRealtimeSTTRunning)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRealtimeSTTRunning) "STT 중지" else "실시간 STT")
            }
        }

        // 기존 더미 테스트 결과 표시 (실시간 STT가 실행 중이 아닐 때만)
        if (transcriptionResult.isNotEmpty() && !isRealtimeSTTRunning) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "더미 테스트 결과:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = transcriptionResult,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // 새로 추가된 실시간 자막 표시 영역
        if (isRealtimeSTTRunning || realtimeResults.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "출력 정보 (${realtimeResults.size}개)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (realtimeResults.isNotEmpty()) {
                            TextButton(
                                onClick = { realtimeResults = listOf() }
                            ) {
                                Text("초기화")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (realtimeResults.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (isRealtimeSTTRunning) "🎤 음성을 인식하고 있습니다..." else "실시간 STT를 시작하세요",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isRealtimeSTTRunning) {
                                    Text(
                                        text = "💡 영어로 말해보세요!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(realtimeResults) { result ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🎯 인식 결과",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = result.substringBefore("]").substringAfter("["),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = result.substringAfter("] "),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // 기존 하단 안내 텍스트 유지
        Text(
            text = "ggml-tiny.en.bin 모델을 assets/models/ 폴더에 추가하세요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}