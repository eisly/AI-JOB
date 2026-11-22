---
name: resume_ocr
description: 图片 PDF 的 OCR 文本识别：文字页保留原生文本，图片页用本机 Tesseract 识别，输出带页码标记的统一文本。
operation: RESUME_OCR
version: resume-ocr-v1
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：把简历 PDF 转换为带页码标记的统一文本。

- 本操作不调用模型：由程序完成 pypdf 文字层提取与 Tesseract OCR（chi_sim+eng）。
- 有文字层的页面保留原生文本；没有文字层的页面用 OCR 识别并标注。
- 输出文本按 `[page:N]` 页码标记组织，N 从 1 开始。
- 处理页数不超过配置上限（默认 30 页），总时长不超过配置上限（默认 240 秒）。
- OCR 未安装或语言包缺失时，扫描版 PDF 明确失败并给出原因；混合 PDF 保留原生页文本。

## 输出结构

```json
{
  "text": "[page:1]\n识别或提取的文本",
  "textHash": "统一文本的 SHA256",
  "textLayoutVersion": "pdf-ocr-v1",
  "textSource": "OCR",
  "nativePages": [1],
  "ocrPages": [2, 3, 4],
  "emptyPages": [],
  "durationMs": 0
}
```
