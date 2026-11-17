# 图像生成

打开**设置 → 图像生成**，选择已配置的 `提供商:模型` 和默认尺寸。模型即使没有启用于普通聊天，也可以从提供商同步或手动添加。选择器优先显示图像模型；其他名称可通过**显示全部**选择。

Agora 复用所选提供商的协议、Base URL 和凭据，不另存一套图像密钥或端点。OpenAI 兼容提供商使用 `/images/generations` 和 multipart `/images/edits`；Google 协议提供商使用开启图像输出的 Gemini 原生 `generateContent`。模型名看起来像图像模型，不代表端点兼容。Imagen 原生接口、厂商异步接口和任意 ComfyUI 工作流使用不同协议。

## 生成与编辑

模型通过 `image_generate` 提交提示词。编辑或参考已有图片时，可传入有序的 `images` 数组，例如 `{ "server": "Local Sandbox", "path": "/absolute/image/path.png" }`，也可指定已启用的 Conch 设备。对应的沙盒或 Shell 权限必须开启。最多接受十张参考图；具体模型可能有更低上限。图片通过现有文件/图像入口读取，并经过 Agora 共享图像处理器，无需在工具参数中放入 Base64。OpenAI 兼容编辑接口还可使用 PNG 蒙版，其尺寸必须与第一张处理后的参考图相同。

OpenAI 兼容调用支持可选的 `size`、`n`（1–4）、`quality`、`background`、`output_format`、`output_compression`、`input_fidelity`、`moderation`、旧式 `response_format` 和本地端点的 `seed`。未提供的控制项不发送；支持值由端点和模型决定。Gemini 原生调用改用 `aspect_ratio`、`image_size`；提交前会拒绝 OpenAI 专用参数和 `n > 1`。默认 `1024x1024` 仅用于 OpenAI 兼容接口；Gemini 未指定专用选项时使用原生默认值。

返回的最终图片按顺序保存在所属工具结果中，不包含 Gemini 的思考中间图片。启用 Local Sandbox 时，处理后的结果写入 `/home/agora/attachments/generated_…/`；工具返回有序的服务器/路径、MIME 类型、文件大小和尺寸，供后续编辑或文件工具使用。沙盒不可用时会在生成前失败，不会悄悄更换存储位置。生成和下载保留十分钟执行预算。提供商失败时，不会自动修改参数再发起一次付费生成。编辑不会覆盖参考图或已发布的对话媒体。

## 本地图像服务与 Qwen Image 2.1

添加自定义 **OpenAI** 提供商，Base URL 指向本地服务的 API 根路径，例如 `http://<电脑地址>:8000/v1`，并添加该服务的准确模型 ID。无认证服务可将 API Key 留空；Agora 会省略 Authorization，不需要伪造密钥。地址必须能从手机访问：Android 上的 `localhost` 指手机自身。随后在图像生成设置中选择该提供商与模型。

Qwen Image 2.1 官方提供 Diffusers `QwenImage21Pipeline`、ComfyUI 集成，以及已有文档的 vLLM-Omni 在线服务。vLLM-Omni 配方使用 `vllm serve Qwen/Qwen-Image-2.1 --omni --port 8091`，提供 JSON `/v1/images/generations` 和 multipart `/v1/images/edits`，返回 `data[].b64_json`。这条命令仅说明上游服务方式，Agora 不会执行它。

文档中的 vLLM-Omni 请求与 Agora 当前 OpenAI 请求有差异：编辑接口重复使用 `image` 字段，Agora 则发送 `image[]`；配方要求每次显式发送 `num_inference_steps=40`，并推荐 `true_cfg_scale=1.0`。服务端默认值不同，该配方最多接受四张参考图。Agora 当前没有这两个采样字段的控制项，因此路由名一致还不能证明能够直连。

可审查的接入路径是：**Agora 自定义 OpenAI 提供商 → Images 兼容适配器 → vLLM-Omni**。提供商根地址填 `http://<手机可达主机>:<适配器端口>/v1`，模型 ID 填 `Qwen/Qwen-Image-2.1`。适配器需将有序 `image[]` 上传映射为重复的 `image` 字段，注入上述采样值，限制四张参考图，并保留 `data[].b64_json` 返回结构。不能实现的蒙版或其他控制项应明确拒绝，不能静默丢弃。只有实际核验后端接受的字段和采样默认值后，才能省去适配器。本文不选定适配器实现、主机安装目录或监听端口。

原生 ComfyUI 则需要工作流适配器：通过 `/upload/image` 上传参考图，向 `/prompt` 提交 API 格式工作流，跟踪该 prompt 的历史，再通过 `/view` 取回输出。加载权重不会自动添加 OpenAI `/images/*` 路由。沙盒服务器/路径描述的是对应设备上的输入文件；Agora 读取字节后上传，不会把手机路径直接交给 GPU 主机。

官方模型卡演示了 2048×2048 生成、图片编辑、固定种子和透明 RGBA 输出。依赖与显存应按实际运行时核验；vLLM-Omni 配方要求 NVIDIA GPU，并明确说明完整图像质量验证仍待完成。这些上游例子不代表本机已经验证。Agora 的图像质量设置仍控制完成后的预览/模型输入图片；需要保留大尺寸结果时，选择高质量或合适的自定义设置。设置页不会下载权重、安装 Python 包或启动服务。

## 数据流

提示词、参考图字节和所选参数会发送给配置的图像提供商，生成媒体随对话和工具结果保存在设备上。参考图读取使用对应已启用设备的凭据；图像提供商密钥不会发送给参考图服务器或返回图片的下载 URL。详见[隐私与安全](privacy.md)。

协议来源：[OpenAI 图像生成](https://developers.openai.com/api/docs/guides/image-generation)、[Gemini 图像生成](https://ai.google.dev/gemini-api/docs/image-generation)、[Qwen Image 2.1 模型卡](https://huggingface.co/Qwen/Qwen-Image-2.1)、[vLLM-Omni Qwen Image 2.1 配方](https://recipes.vllm.ai/Qwen/Qwen-Image-2.1)、[ComfyUI 集成](https://comfy.org/qwen-image-2.1/)及 [ComfyUI 服务路由](https://docs.comfy.org/development/comfyui-server/comms_routes)。
