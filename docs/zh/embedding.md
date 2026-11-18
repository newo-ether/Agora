# 嵌入 / RAG

嵌入模型把对话文本转换为向量，供语义对话搜索使用。请在**设置 → 对话搜索**中配置。

## 提供商与预设

当前预设包括：

- OpenAI：`text-embedding-3-small`、`text-embedding-3-large`、`text-embedding-ada-002`
- Mistral：`mistral-embed`
- Voyage AI：`voyage-3-large`、`voyage-3-lite`、`voyage-code-3`
- SiliconFlow：`BAAI/bge-m3`、`BAAI/bge-large-en-v1.5`
- OpenRouter 的 OpenAI 嵌入模型路由
- Requesty 的 OpenAI 嵌入模型路由
- Ollama、本地嵌入模型或自定义端点

远程嵌入使用所选提供商的凭据和 Base URL，待嵌入文本会离开设备；本地嵌入留在设备上。

## RAG 控制

- 上下文范围：4–32 个对话步骤，步长 4
- 结果数：5–30，步长 5
- 相似度阈值：0–1，默认 0.5

更换嵌入模型后，旧索引可能需要重新生成。详见[对话搜索](search.md)和[隐私与安全](privacy.md)。
