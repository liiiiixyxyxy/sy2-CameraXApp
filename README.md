# CameraXApp - Android 相机应用实验

基于 Android CameraX 构建的相机应用，实现了 Preview、ImageCapture、VideoCapture 和 ImageAnalysis 四大核心功能，并展示了它们的协同工作能力。

##  实验概述

本项目基于 CSDN 教程 [Android CameraX的基础使用](https://blog.csdn.net/llfjfz/article/details/129924593) 实现，完整实现了 CameraX 的四大核心用例，并演示了 Preview + VideoCapture + ImageAnalysis 的组合使用。

## 功能特性

### 1. Preview（预览功能）
- 实时相机画面预览
- 全屏显示相机捕获的画面
- 使用 `PreviewView` 组件实现

**核心代码**:

```kotlin
// 创建 Preview 用例
val preview = Preview.Builder()
    .build()
    .also {
        // 将预览画面绑定到 PreviewView
        it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
    }

// 布局文件中的 PreviewView
<androidx.camera.view.PreviewView
    android:id="@+id/viewFinder"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

**工作原理**:
- `Preview.Builder()` 创建预览配置
- `setSurfaceProvider()` 将相机输出绑定到 UI 组件
- `PreviewView` 自动处理相机预览的生命周期

---

### 2. ImageCapture（拍照功能）
- 点击按钮捕获照片
- 自动保存到相册 `Pictures/CameraX-Image/` 目录
- 支持 JPEG 格式输出

**核心代码**:

```kotlin
// 初始化 ImageCapture
imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)  // 低延迟模式
    .build()

// 拍照方法
private fun takePhoto() {
    val imageCapture = imageCapture ?: return

    // 设置保存路径和文件名
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
        }
    }

    // 创建输出选项
    val outputOptions = ImageCapture.OutputFileOptions
        .Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        .build()

    // 执行拍照
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(this),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val msg = "Photo capture succeeded: ${outputFileResults.savedUri}"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
            }
        }
    )
}
```

**工作流程**:
1. 创建 `ImageCapture` 实例
2. 配置保存路径到系统相册
3. 调用 `takePicture()` 捕获照片
4. 通过回调处理成功/失败状态

---

### 3. VideoCapture（视频录制功能）
- 点击按钮开始/停止录制
- 自动保存到相册 `Movies/CameraX-Video/` 目录
- 支持最高质量视频录制
- 录制状态实时指示

**核心代码**:

```kotlin
// 初始化 VideoCapture
val recorder = Recorder.Builder()
    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))  // 最高质量
    .build()
videoCapture = VideoCapture.withOutput(recorder)

// 录制控制方法
private fun captureVideo() {
    val videoCapture = videoCapture ?: return

    // 如果正在录制，停止录制
    val curRecording = recording
    if (curRecording != null) {
        curRecording.stop()
        recording = null
        recordingIndicator.visibility = View.GONE
        return
    }

    // 设置视频保存配置
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
        }
    }

    val mediaStoreOutputOptions = MediaStoreOutputOptions
        .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        .setContentValues(contentValues)
        .build()

    // 开始录制
    recordingIndicator.visibility = View.VISIBLE  // 显示录制指示灯
    recording = videoCapture.output
        .prepareRecording(this, mediaStoreOutputOptions)
        .withAudioEnabled()  // 启用音频录制（需 RECORD_AUDIO 权限）
        .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    // 录制开始
                    videoCaptureButton.text = getString(R.string.stop_capture)
                }
                is VideoRecordEvent.Finalize -> {
                    // 录制结束
                    recordingIndicator.visibility = View.GONE
                    videoCaptureButton.text = getString(R.string.start_capture)
                    
                    if (!recordEvent.hasError()) {
                        val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
}
```

**工作流程**:
1. 创建 `Recorder` 配置视频质量
2. 通过 `VideoCapture.withOutput()` 创建视频捕获实例
3. 点击按钮开始录制，显示红色指示灯
4. 再次点击停止录制，保存到相册

---

### 4. ImageAnalysis（图像分析功能）
- **亮度检测**: 实时计算画面平均亮度值（0-255）
- **色彩分析**: 识别画面主色调（Reddish/Greenish/Bluish等）
- **帧率显示**: 实时显示分析帧率

**核心代码**:

```kotlin
// 初始化 ImageAnalysis
val imageAnalyzer = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // 只处理最新帧
    .build()
    .also {
        it.setAnalyzer(cameraExecutor) { imageProxy ->
            analyzeImage(imageProxy)
        }
    }

// 图像分析核心方法
private fun analyzeImage(imageProxy: ImageProxy) {
    // 1. 计算帧率
    frameCount++
    val currentTime = System.currentTimeMillis()
    val elapsedTime = currentTime - lastFpsUpdateTime
    
    if (elapsedTime >= 1000) {
        val fps = frameCount * 1000.0 / elapsedTime
        runOnUiThread {
            frameRateText.text = String.format(Locale.US, "FPS: %.1f", fps)
        }
        frameCount = 0
        lastFpsUpdateTime = currentTime
    }

    // 2. 获取图像数据
    val buffer = imageProxy.planes[0].buffer
    val data = buffer.remaining()
    val pixelArray = ByteArray(data)
    buffer.get(pixelArray)

    // 3. 分析像素数据（采样处理，提高性能）
    var sumBrightness = 0L
    var sumRed = 0L
    var sumGreen = 0L
    var sumBlue = 0L
    val step = 4  // 每隔4个像素采样一次
    val sampleCount = pixelArray.size / (3 * step)

    for (i in 0 until pixelArray.size step 3 * step) {
        val r = pixelArray[i].toInt() and 0xFF
        val g = pixelArray[i + 1].toInt() and 0xFF
        val b = pixelArray[i + 2].toInt() and 0xFF

        sumRed += r
        sumGreen += g
        sumBlue += b

        // 使用加权公式计算亮度: Y = 0.299*R + 0.587*G + 0.114*B
        val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        sumBrightness += brightness
    }

    // 4. 计算平均值
    val avgBrightness = if (sampleCount > 0) (sumBrightness / sampleCount).toInt() else 0
    val avgRed = if (sampleCount > 0) (sumRed / sampleCount).toInt() else 0
    val avgGreen = if (sampleCount > 0) (sumGreen / sampleCount).toInt() else 0
    val avgBlue = if (sampleCount > 0) (sumBlue / sampleCount).toInt() else 0

    // 5. 判断亮度等级
    val brightnessLevel = when {
        avgBrightness < 50 -> "Very Dark"
        avgBrightness < 100 -> "Dark"
        avgBrightness < 150 -> "Dim"
        avgBrightness < 200 -> "Normal"
        avgBrightness < 230 -> "Bright"
        else -> "Very Bright"
    }

    // 6. 判断主色调
    val dominantColor = when {
        avgRed > avgGreen && avgRed > avgBlue -> "Reddish"
        avgGreen > avgRed && avgGreen > avgBlue -> "Greenish"
        avgBlue > avgRed && avgBlue > avgGreen -> "Bluish"
        avgRed > 200 && avgGreen > 200 && avgBlue > 200 -> "Whiteish"
        avgRed < 50 && avgGreen < 50 && avgBlue < 50 -> "Blackish"
        else -> "Neutral"
    }

    // 7. 更新UI显示
    runOnUiThread {
        brightnessText.text = "Brightness: $avgBrightness ($brightnessLevel)"
        colorText.text = "Color: $dominantColor (R:$avgRed G:$avgGreen B:$avgBlue)"
    }

    // 8. 释放图像资源（必须调用）
    imageProxy.close()
}
```

**分析算法说明**:
- **帧率计算**: 通过统计每秒处理的帧数实现
- **像素采样**: 每隔4个像素采样一次，平衡性能与精度
- **亮度计算**: 使用标准加权公式 `Y = 0.299R + 0.587G + 0.114B`
- **主色调判断**: 通过比较 RGB 三个通道的平均值确定

---

##  技术实现

### 核心代码结构

**四大功能绑定到相机生命周期**:

```kotlin
private fun startCamera() {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

    cameraProviderFuture.addListener({
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

        // 1. 创建 Preview 用例
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

        // 2. 创建 ImageCapture 用例
        imageCapture = ImageCapture.Builder().build()

        // 3. 创建 VideoCapture 用例
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // 4. 创建 ImageAnalysis 用例
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeImage(imageProxy)
                }
            }

        // 选择后置摄像头
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // 绑定所有用例到相机生命周期
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer
        )

    }, ContextCompat.getMainExecutor(this))
}
```

### 功能协作流程

```
┌─────────────────────────────────────────────────────────────┐
│                    CameraProvider                          │
│                     (相机硬件/驱动层)                       │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
              ┌──────────────────────────────┐
              │     CameraSelector           │
              │      (后置摄像头)              │
              └──────────────┬───────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
    ┌─────────┐        ┌──────────┐        ┌──────────┐
    │ Preview │        │VideoCap- │        │ImageAna- │
    │         │        │ture      │        │lysis     │
    └────┬────┘        └────┬─────┘        └────┬─────┘
         │                  │                  │
         ▼                  │                  │
┌────────────────┐          │                  │
│ PreviewView    │          │                  │
│ (实时预览)      │          │                  │
└────────────────┘          │                  │
                            ▼                  ▼
                     ┌────────────┐     ┌────────────┐
                     │ 保存到     │     │ 分析帧数据 │
                     │ Movies/... │     │ 更新UI显示 │
                     └────────────┘     └────────────┘
```

---

##  界面说明

### 主界面布局

| 位置 | 组件 | 功能说明 |
|------|------|----------|
| **全屏** | PreviewView | 相机实时预览画面 |
| **左上角** | 分析面板 | 显示亮度、色彩、帧率信息 |
| **右上角** | 录制指示灯 | 红色圆点表示正在录制 |
| **底部左侧** | 拍照按钮 | 点击捕获照片 |
| **底部右侧** | 录像按钮 | 点击开始/停止录制 |

### 截图说明

#### 1. 相机预览界面
![相机预览界面](docs/screenshots/preview.png)

**功能说明**:
- 全屏显示相机预览画面
- 左上角显示实时分析数据：
  - **Brightness**: 画面平均亮度值和等级（0-255）
  - **Color**: 主色调分析和RGB分量
  - **FPS**: 图像分析帧率

#### 2. 拍照功能
![请求使用摄像头和麦克风](screensshots/请求使用摄像头和麦克风)
![拍照功能]


**功能说明**:
- 点击左侧 "Take Photo" 按钮拍照
- 照片自动保存到系统相册 `Pictures/CameraX-Image/`
- 拍照时图像分析继续运行，不中断

#### 3. 视频录制功能
![视频录制](VideoCapture)
**功能说明**:
- 点击右侧 "Start Capture" 按钮开始录制
- 右上角显示红色录制指示灯（录制中）
- 按钮文字变为 "Stop Capture"
- 录制期间图像分析持续运行

#### 4. Preview + VideoCapture + ImageAnalysis 结合
![三功能结合](Preview + VideoCapture + ImageAnalysis)

**功能说明**:
- **Preview**: 实时显示相机画面
- **VideoCapture**: 正在录制视频（红色指示灯亮）
- **ImageAnalysis**: 实时分析画面亮度、色彩、帧率
- 三个功能共享同一相机流，同步工作

---

##  快速开始

### 环境要求
- Android Studio Hedgehog 或更高版本
- 最低 API Level 21
- CameraX 版本 1.3.0

### 依赖配置

```gradle
dependencies {
    def camerax_version = "1.3.0"
    
    // CameraX 核心库
    implementation "androidx.camera:camera-core:${camerax_version}"
    implementation "androidx.camera:camera-camera2:${camerax_version}"
    implementation "androidx.camera:camera-lifecycle:${camerax_version}"
    
    // 视频录制支持
    implementation "androidx.camera:camera-video:${camerax_version}"
    
    // View 组件
    implementation "androidx.camera:camera-view:${camerax_version}"
    implementation "androidx.camera:camera-extensions:${camerax_version}"
    
    // 其他依赖
    implementation "androidx.appcompat:appcompat:1.6.1"
    implementation "androidx.constraintlayout:constraintlayout:2.1.4"
}
```

### 权限要求

```xml
<!-- 相机权限 -->
<uses-permission android:name="android.permission.CAMERA" />

<!-- 录音权限（视频录制需要） -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- 存储权限（保存照片/视频需要） -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<!-- 声明相机功能 -->
<uses-feature android:name="android.hardware.camera" />
<uses-feature android:name="android.hardware.camera.autofocus" />
```

---

##  项目结构

```
CameraXApp/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/android/example/cameraxapp/
│   │       │   └── MainActivity.kt    # 主Activity，包含所有相机逻辑
│   │       └── res/
│   │           ├── drawable/
│   │           │   └── recording_dot.xml  # 录制指示点资源
│   │           ├── layout/
│   │           │   └── activity_main.xml  # 主界面布局
│   │           └── values/
│   │               └── strings.xml        # 字符串资源
│   └── build.gradle.kts                  # 模块配置
├── docs/
│   └── screenshots/                      # 截图目录（用户自行补充）
└── README.md                             # 项目说明文档
```

---

##  实验总结

### CameraX 四大用例对比

| 用例 | 功能 | 数据输出 | 典型场景 |
|------|------|----------|----------|
| **Preview** | 实时预览 | 显示画面 | 取景、监控 |
| **ImageCapture** | 拍照 | 静态图片 | 拍照、证件照 |
| **VideoCapture** | 录像 | 视频文件 | 短视频、直播 |
| **ImageAnalysis** | 图像分析 | 帧数据回调 | 人脸检测、OCR、QR码识别 |

### Preview + VideoCapture + ImageAnalysis 结合优势

1. **资源共享**: 三个功能共享同一相机流，节省系统资源
2. **实时同步**: 分析结果与录制画面完全同步
3. **低延迟**: 分析结果实时更新到UI
4. **功能扩展**: 可在此基础上添加人脸识别、运动检测等高级功能

---
