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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hiclone.whisperstt.ui.theme.WhisperSTTTheme
import kotlinx.coroutines.launch

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
                    EnhancedWhisperStreamingScreen(whisperSTT, this@MainActivity)
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
fun EnhancedWhisperStreamingScreen(whisperSTT: WhisperSTT, activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 상태 관리
    var statusText by remember { mutableStateOf("Whisper Streaming STT 준비") }
    var isModelLoaded by remember { mutableStateOf(false) }
    var isRealtimeSTTRunning by remember { mutableStateOf(false) }
    var selectedProcessorType by remember { mutableStateOf(StreamingProcessorType.WHISPER_STREAMING) }
    
    data class TranscriptionItem(
        val text: String,
        val timestamp: Long,
        val isFinal: Boolean,
        val processorType: String,
        val id: String = "${System.currentTimeMillis()}_${text.hashCode()}"
    )
    
    // 실시간 전사 결과 관리
    var realtimeResults by remember { mutableStateOf<List<TranscriptionItem>>(emptyList()) }
    var currentPartialText by remember { mutableStateOf("") }
    var performanceStats by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // 스트리밍 시작 함수
    fun startStreaming() {
        isRealtimeSTTRunning = whisperSTT.startRealtimeSTT(
            onResult = { result ->
                val currentTime = System.currentTimeMillis()
                
                when {
                    result.startsWith("✅ ") -> {
                        // 확정된 텍스트
                        val finalText = result.removePrefix("✅ ").trim()
                        if (finalText.isNotEmpty()) {
                            realtimeResults = realtimeResults + TranscriptionItem(
                                text = finalText,
                                timestamp = currentTime,
                                isFinal = true,
                                processorType = selectedProcessorType.displayName
                            )
                            currentPartialText = "" // 부분 텍스트 클리어
                        }
                    }
                    result.startsWith("⚡ ") -> {
                        // 부분적 텍스트 (실시간 업데이트)
                        val partialText = result.removePrefix("⚡ ").trim()
                        currentPartialText = partialText
                    }
                    result.startsWith("🔄 ") -> {
                        // 슬라이딩 윈도우 결과
                        val slidingText = result.removePrefix("🔄 ").trim()
                        if (slidingText.isNotEmpty()) {
                            realtimeResults = realtimeResults + TranscriptionItem(
                                text = slidingText,
                                timestamp = currentTime,
                                isFinal = false,
                                processorType = selectedProcessorType.displayName
                            )
                        }
                    }
                    else -> {
                        // 기타 결과
                        if (result.trim().isNotEmpty()) {
                            realtimeResults = realtimeResults + TranscriptionItem(
                                text = result.trim(),
                                timestamp = currentTime,
                                isFinal = false,
                                processorType = selectedProcessorType.displayName
                            )
                        }
                    }
                }
            },
            onStatus = { status ->
                statusText = status
                isRealtimeSTTRunning = whisperSTT.isRealtimeSTTRunning()
                
                // 성능 통계 업데이트
                if (status.contains("성능") || status.contains("지연시간")) {
                    performanceStats = status
                }
            },
            processorType = selectedProcessorType
        )
    }

        // 권한 요청 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startStreaming()
        } else {
            statusText = "마이크 권한이 필요합니다"
        }
    }

    // 앱 시작 시 모델 로드 상태 확인
    LaunchedEffect(Unit) {
        // 네이티브 라이브러리 로드 상태 확인
        if (whisperSTT.isNativeLibraryLoaded()) {
            // 모델이 이미 로드되어 있는지 확인
            isModelLoaded = whisperSTT.isLoaded()
            if (isModelLoaded) {
                statusText = "모델이 이미 로드되어 있습니다"
            } else {
                statusText = "모델을 로드해주세요"
            }
        } else {
            statusText = "네이티브 라이브러리 로드 실패"
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Whisper Streaming STT",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // 상태 카드
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "상태: $statusText",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "모델: ${if (isModelLoaded) "whisper-tiny 로드됨" else "모델 미로드"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "프로세서: ${selectedProcessorType.displayName}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "실시간 STT: ${if (isRealtimeSTTRunning) "실행 중" else "대기 중"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRealtimeSTTRunning)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (realtimeResults.isNotEmpty()) {
                    Text(
                        text = "전사 결과: ${realtimeResults.size}개 (확정: ${realtimeResults.count { item -> item.isFinal }}개)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (performanceStats.isNotEmpty()) {
                    Text(
                        text = performanceStats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 프로세서 선택 (토글 형식)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "스트리밍 프로세서 선택",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamingProcessorType.values().forEach { processorType ->
                        FilterChip(
                            selected = selectedProcessorType == processorType,
                            onClick = { 
                                if (!isRealtimeSTTRunning) {
                                    selectedProcessorType = processorType
                                }
                            },
                            enabled = !isRealtimeSTTRunning,
                            label = {
                                Text(
                                    text = processorType.displayName,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 컨트롤 버튼들 (한 줄에 4개)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 모델 로드 버튼
            Button(
                onClick = {
                    statusText = "모델 로드 중..."
                    
                    // 네이티브 라이브러리 로드 상태 확인
                    if (!whisperSTT.isNativeLibraryLoaded()) {
                        isModelLoaded = false
                        statusText = "네이티브 라이브러리 로드 실패"
                        return@Button
                    }
                    
                    // 이미 로드되어 있는지 확인
                    if (whisperSTT.isLoaded()) {
                        isModelLoaded = true
                        statusText = "모델이 이미 로드되어 있습니다"
                        return@Button
                    }
                    
                    val result = whisperSTT.loadModelFromAssets(activity, "models/ggml-tiny.en.bin")
                    isModelLoaded = result
                    statusText = if (result) "tiny 모델 로드 성공!" else "모델 로드 실패"
                },
                modifier = Modifier.weight(1f),
                enabled = !isRealtimeSTTRunning
            ) {
                Text("모델 로드", style = MaterialTheme.typography.bodySmall)
            }

            // 실시간 STT 토글 버튼
            Button(
                onClick = {
                    if (isRealtimeSTTRunning) {
                        // STT 중지 - 즉시 UI 상태 업데이트
                        isRealtimeSTTRunning = false
                        statusText = "실시간 STT 중지 중..."
                        
                        // 백그라운드에서 STT 중지 처리
                        scope.launch {
                            try {
                                whisperSTT.stopRealtimeSTT()
                                // UI 상태 최종 업데이트
                                currentPartialText = ""
                                statusText = "실시간 STT 중지됨"
                                performanceStats = ""
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error stopping STT", e)
                                statusText = "STT 중지 오류"
                            }
                        }
                    } else {
                        // 모델 로드 상태 재확인
                        if (!whisperSTT.isLoaded()) {
                            statusText = "먼저 모델을 로드해주세요"
                            isModelLoaded = false
                            return@Button
                        }
                        
                        // 마이크 권한 체크 후 STT 시작
                        when (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        )) {
                            PackageManager.PERMISSION_GRANTED -> {
                                startStreaming()
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
                Text(if (isRealtimeSTTRunning) "중지" else "시작", style = MaterialTheme.typography.bodySmall)
            }

            // 결과 초기화 버튼
            Button(
                onClick = { 
                    realtimeResults = listOf()
                    currentPartialText = ""
                    performanceStats = ""
                },
                modifier = Modifier.weight(1f),
                enabled = realtimeResults.isNotEmpty() && !isRealtimeSTTRunning
            ) {
                Text("초기화", style = MaterialTheme.typography.bodySmall)
            }

            // 성능 테스트 버튼
            Button(
                onClick = {
                    statusText = "더미 오디오 테스트 중..."
                    
                    // 테스트용 더미 오디오 데이터 (1초, 16kHz)
                    val dummyAudio = FloatArray(16000) { 
                        (Math.sin(2 * Math.PI * 440 * it / 16000.0)).toFloat() 
                    }

                    // 백그라운드에서 음성 인식 실행
                    whisperSTT.transcribeAudio(dummyAudio) { result ->
                        activity.runOnUiThread {
                            val currentTime = System.currentTimeMillis()
                            realtimeResults = realtimeResults + TranscriptionItem(
                                text = "테스트: $result",
                                timestamp = currentTime,
                                isFinal = true,
                                processorType = "더미 테스트"
                            )
                            statusText = "더미 테스트 완료"
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = isModelLoaded && !isRealtimeSTTRunning
            ) {
                Text("테스트", style = MaterialTheme.typography.bodySmall)
            }
        }

        // 실시간 부분 텍스트 표시
        if (currentPartialText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "실시간 인식 중... (${selectedProcessorType.displayName})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentPartialText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // 전사 결과 표시 영역
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
                        text = "전사 결과",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (realtimeResults.isNotEmpty()) {
                        Text(
                            text = "${realtimeResults.size}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (realtimeResults.isEmpty() && currentPartialText.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isRealtimeSTTRunning) 
                                    "음성을 인식하고 있습니다..." 
                                else "실시간 STT를 시작하세요",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            if (isRealtimeSTTRunning) {
                                Text(
                                    text = """
                                        영어로 말해보세요!
                                        부분 결과가 실시간으로 표시됩니다
                                        확정된 결과가 아래에 저장됩니다
                                        
                                        현재 사용 중: ${selectedProcessorType.displayName}
                                    """.trimIndent(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
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
                        items(realtimeResults, key = { item -> item.id }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        item.isFinal -> MaterialTheme.colorScheme.primaryContainer
                                        item.processorType.contains("슬라이딩") -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.secondaryContainer
                                    }
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = if (item.isFinal) "확정" else "임시",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (item.isFinal)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.secondary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = item.processorType,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = android.text.format.DateFormat.format(
                                                "HH:mm:ss", item.timestamp
                                            ).toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (item.isFinal) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}