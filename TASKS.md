# aiworder 任务清单

## DeepSeek 云端大模型

- [x] 模型管理页添加独立的 DeepSeek API Key 卡片
- [x] SecureStorage 添加 deepseek_key 独立存取方法
- [x] ModelsViewModel 添加 deepseekKey/deepseekModel StateFlow
- [x] ChatViewModel 集成 DeepSeek 云端流式调用
- [x] VoiceCallViewModel 集成 DeepSeek 云端调用
- [ ] 编译验证通过 (./gradlew assembleDebug)

## 讯飞云端语音识别

- [x] 模型管理页添加讯飞凭证配置卡片
- [x] SecureStorage 添加 xfyun_app_id/api_key/api_secret 存取方法
- [x] VoicePipeline 集成 XfyunAsrProvider
- [x] ASR 提供商切换 (讯飞 / Cloudflare)

## 云端优先模式

- [x] ChatViewModel 默认优先使用云端模型，有云端Key时跳过本地模型加载
- [x] VoiceCallViewModel 支持云端模式
- [ ] 编译验证通过 (./gradlew assembleDebug)

## 待规划

- [ ] DeepSeek R1 reasoning_content 过滤
- [ ] 多云端模型提供商标识切换 (OpenAI / DeepSeek / 自定义)
