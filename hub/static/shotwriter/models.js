// Target model profiles, rebuilt from official / authoritative prompt guides (links in `sources`).
// Fields:
//   guide      – rules the LLM must follow for this model (sent in the system prompt)
//   words      – target word range (see lengthNote for where it comes from)
//   maxChars   – hard prompt character limit (enforced after generation), maxNegChars likewise
//   negative   – model has a separate negative-prompt field
//   negInline  – no negative field: exclusions go inside the prompt as plain constraints
//   audio / audioRule – native audio + the exact syntax the model's guide documents
//   multishot / multiRule – multi-shot support + the exact syntax
//   structured – guide uses line breaks / labelled blocks (otherwise one paragraph)
//   example    – short example prompt quoted from the guide
//   settings   – suggested generation settings shown next to the output (not sent to the LLM)

const WAN_NEG_ZH = "色调艳丽，过曝，静态，细节模糊不清，字幕，风格，作品，画作，画面，静止，整体发灰，最差质量，低质量，JPEG压缩残留，丑陋的，残缺的，多余的手指，画得不好的手部，画得不好的脸部，畸形的，毁容的，形态畸形的肢体，手指融合，静止不动的画面，杂乱的背景，三条腿，背景人很多，倒着走";
// Official English translation from the Wan 2.1 README (Diffusers example)
const WAN_NEG_EN = "Bright tones, overexposed, static, blurred details, subtitles, style, works, paintings, images, static, overall gray, worst quality, low quality, JPEG compression residue, ugly, incomplete, extra fingers, poorly drawn hands, poorly drawn faces, deformed, disfigured, misshapen limbs, fused fingers, still picture, messy background, three legs, many people in the background, walking backwards";

const SRC = {
  wan21Readme: { label: "Wan 2.1 README (official)", url: "https://github.com/Wan-Video/Wan2.1/blob/main/README.md" },
  wan21Extend: { label: "Wan 2.1 prompt-extension system prompt (official code)", url: "https://github.com/Wan-Video/Wan2.1/blob/main/wan/utils/prompt_extend.py" },
  wan21Cfg: { label: "Wan 2.1 shared config (fps, negative prompt)", url: "https://github.com/Wan-Video/Wan2.1/blob/main/wan/configs/shared_config.py" },
  wan22Readme: { label: "Wan 2.2 README (official)", url: "https://github.com/Wan-Video/Wan2.2/blob/main/README.md" },
  wan22Sys: { label: "Wan 2.2 prompt-extension system prompts (official code)", url: "https://github.com/Wan-Video/Wan2.2/blob/main/wan/utils/system_prompt.py" },
  wan22T2V: { label: "Wan 2.2 T2V-A14B config", url: "https://github.com/Wan-Video/Wan2.2/blob/main/wan/configs/wan_t2v_A14B.py" },
  wan22I2V: { label: "Wan 2.2 I2V-A14B config", url: "https://github.com/Wan-Video/Wan2.2/blob/main/wan/configs/wan_i2v_A14B.py" },
  wan22TI2V: { label: "Wan 2.2 TI2V-5B config", url: "https://github.com/Wan-Video/Wan2.2/blob/main/wan/configs/wan_ti2v_5B.py" },
  wanGuide: { label: "Wan prompt formula guide (mirror of Alibaba guide)", url: "https://wan2-1.com/blogs/wan-2-2-prompting-guide" },
  wan26: { label: "fal – Wan 2.6 prompt guide", url: "https://fal.ai/learn/devs/wan-2-6-prompt-guide-mastering-all-three-generation-modes" },
  seedance25: { label: "Seedance 2.5 prompting guide", url: "https://docs.seedance.tv/en/seedance-2-5-prompting-guide" },
  seedance20: { label: "BytePlus ModelArk – Seedance 2.0 prompt guide (official)", url: "https://docs.byteplus.com/en/docs/ModelArk/2222480" },
  klingGuide: { label: "Kling AI prompt guide (official)", url: "https://kling.ai/blog/kling-ai-prompt-guide" },
  klingApi: { label: "Kling API reference – text to video (official)", url: "https://kling.ai/document-api/apiReference/model/textToVideo" },
  klingOmni: { label: "Kling 3 prompt syntax & Omni reference tags (official blog)", url: "https://kling.ai/blog/kling-3-prompt-syntax-omni-reference-tags-video-physics" },
  klingFal: { label: "fal – Kling 3.0 prompting guide", url: "https://blog.fal.ai/kling-3-0-prompting-guide/" },
  veo: { label: "Google Cloud – Ultimate prompting guide for Veo 3.1", url: "https://cloud.google.com/blog/products/ai-machine-learning/ultimate-prompting-guide-for-veo-3-1" },
  googleVideo: { label: "Google Cloud – video generation prompt guide", url: "https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/video/video-gen-prompt-guide" },
  sora: { label: "OpenAI Cookbook – Sora 2 prompting guide", url: "https://developers.openai.com/cookbook/examples/sora/sora2_prompting_guide.md" },
  hailuoFal: { label: "fal – MiniMax Hailuo-02 Pro (camera commands)", url: "https://fal.ai/models/fal-ai/minimax/hailuo-02/pro/text-to-video" },
  hailuoNews: { label: "MiniMax – Hailuo 02 start/end frames (official)", url: "https://www.minimax.io/news/minimax-hailuo-02-start-end-frames-feature-is-now-live" },
  omni: { label: "Gemini API – Generate and edit videos with Gemini Omni Flash", url: "https://ai.google.dev/gemini-api/docs/omni" },
  omniModel: { label: "Gemini API – Gemini Omni Flash model card", url: "https://ai.google.dev/gemini-api/docs/models/gemini-omni-flash" },
  hhMorphic: { label: "Morphic – HappyHorse 1.0 guide", url: "https://morphic.com/resources/how-to/happy-horse-1-0-complete-guide-prompts-features-tips" },
  hhArcads: { label: "Arcads – HappyHorse prompting guide", url: "https://intercom.help/arcads/en/articles/14782448-happy-horse-prompting-guide" },
  grokVideo: { label: "xAI docs – video generation (official)", url: "https://docs.x.ai/developers/model-capabilities/video/generation" },
  grokFal: { label: "fal – How to use Grok Imagine", url: "https://fal.ai/learn/tools/how-to-use-grok-imagine" },
  flux3: { label: "BFL – Video generation with FLUX (official)", url: "https://docs.bfl.ai/guides/prompting_video_overview" },
  flux3Audio: { label: "BFL – Audio and speech prompting (official)", url: "https://docs.bfl.ml/guides/prompting_video_audio" },
  flux3Api: { label: "BFL – FLUX 3 video API (official)", url: "https://docs.bfl.ai/flux_3/flux3_video" },
  runway: { label: "Runway – Gen-4 video prompting guide (official)", url: "https://help.runwayml.com/hc/en-us/articles/39789879462419-Gen-4-Video-Prompting-Guide" },
  runwayCreate: { label: "Runway – Creating with Gen-4 video (official)", url: "https://help.runwayml.com/hc/en-us/articles/37327109429011-Creating-with-Gen-4-Video" },
  nano: { label: "Google – Prompting tips for Nano Banana Pro (official)", url: "https://blog.google/products-and-platforms/products/gemini/prompting-tips-nano-banana-pro/" },
  gptImage: { label: "OpenAI Cookbook – image generation prompting guide", url: "https://developers.openai.com/cookbook/examples/multimodal/image-gen-models-prompting-guide" },
  flux2: { label: "BFL – FLUX.2 prompting guide (official)", url: "https://docs.bfl.ml/guides/prompting_guide_flux2" },
  seedream: { label: "Morphic – Seedream 5.0 Pro guide", url: "https://morphic.com/resources/how-to/seedream-5-pro-guide" },
  qwen: { label: "Runware – Qwen-Image 3.0 prompting guide", url: "https://runware.ai/docs/models/alibaba-qwen-image-3-0/guides/prompting" },
  grokImage: { label: "xAI docs – image generation (official)", url: "https://docs.x.ai/developers/model-capabilities/images/generation" },
};

window.MODEL_GROUPS = [
  { id: "wan", label: "Wan (local / ComfyUI)" },
  { id: "ss-video", label: "SocialSight video" },
  { id: "ss-image", label: "SocialSight image" },
  { id: "universal", label: "Universal" },
];

window.MODELS = [
  // ---------- WAN LOCAL ----------
  {
    id: "wan21-13b", group: "wan", name: "Wan 2.1 · T2V 1.3B", short: "Wan 2.1 1.3B",
    type: "video", modes: ["t2v"], words: [80, 100], negative: true, audio: false,
    lengthNote: "80–100 words is the target in Wan 2.1's official prompt-extension system prompt.",
    defaultNegative: { en: WAN_NEG_EN, zh: WAN_NEG_ZH },
    guide: `Follow the official Wan 2.1 prompt-extension rules (the rewriter Alibaba ships with the model):
- If a style is given, put the STYLE FIRST (e.g. "Japanese-style fresh film photography, ..." / "CG game concept digital art, ...").
- Then the subject with concrete main features: appearance, expression, quantity, ethnicity, posture, clothing.
- Emphasise motion and camera movement. Give the subject natural actions using simple, direct verbs.
- Add spatial relationships and background detail.
- END with the shot scale / angle as a short closing phrase (e.g. "Medium shot half-body portrait in a seated position." / "Close-up, low-angle view.").
- Keep any text that must appear on screen in quotes, unchanged.
- Formula reference (Alibaba guide): Subject (description) + Scene (description) + Motion (description) + Camera language + Atmosphere + Styling.
- 1.3B is a small model: one main subject (two at most), one continuous action, one simple camera move. 480p is the officially recommended resolution for this model.
- One flowing paragraph, no lists, no markdown.`,
    example: `Two anthropomorphic cats in comfy boxing gear and bright gloves fight intensely on a spotlighted stage.`,
    settings: { Resolution: "832×480 (480p recommended; 720p less stable)", Frames: "81 @ 16 fps (~5 s)", "Guide scale": "6", "Sample shift": "8–12 (tune by result)", Steps: "50 (default)", Solver: "unipc (default)" },
    sources: [SRC.wan21Readme, SRC.wan21Extend, SRC.wan21Cfg, SRC.wanGuide],
  },
  {
    id: "wan22-5b", group: "wan", name: "Wan 2.2 · TI2V 5B", short: "Wan 2.2 5B",
    type: "video", modes: ["t2v", "i2v"], words: [60, 200], negative: true, audio: false,
    lengthNote: "60–200 words for T2V and ≤100 words for I2V, from Wan 2.2's official prompt-extension system prompts.",
    i2vWords: [30, 100],
    defaultNegative: { en: WAN_NEG_EN, zh: WAN_NEG_ZH },
    guide: `Wan 2.2 TI2V 5B (one model for text- and image-to-video, 720p @ 24 fps). Follow the official Wan 2.2 prompt-extension rules:
TEXT-TO-VIDEO
- Open with up to 4 cinematic aesthetic tags chosen ONLY from Wan 2.2's official vocabulary, e.g. "Edge lighting, medium close-up shot, daylight, left-heavy composition." Vocabulary: time (Day time, Night time, Dawn time, Sunrise time — default Day time); light source (Daylight, Artificial lighting, Moonlight, Practical lighting, Firelight, Fluorescent lighting, Overcast lighting, Sunny lighting); intensity (Soft lighting, Hard lighting); tone (Warm colors, Cool colors, Mixed colors); light angle (Top lighting, Side lighting, Underlighting, Edge lighting); shot size (Medium shot, Medium close-up shot, Wide shot, Medium wide shot, Close-up shot, Extreme close-up shot, Extreme wide shot — default Medium or Wide); angle (Over-the-shoulder shot, Low angle shot, High angle shot, Dutch angle shot, Aerial shot, Overhead shot — skip if a camera move is described); composition (Center, Balanced, Right-heavy, Left-heavy, Symmetrical, Short-side composition).
- If the user names a style, put the style FIRST; for 2D/illustration styles drop the film-aesthetic tags.
- Then the subject's concrete features (appearance, expression, number, posture). Never add subjects that are not in the idea.
- Describe HOW the action unfolds step by step; if there is no action, add one, and add background motion (clouds drifting, leaves in wind).
- No literary mood sentences like "the scene is full of energy". If the sky appears, describe it as a clear blue sky (avoids overexposure).
IMAGE-TO-VIDEO
- Describe only the dynamic content: the main subject's actions and the camera movement ("the camera pans up", "the camera moves left, then pushes forward"). Remove static descriptions of what the image already shows. Max ~100 words.
- One flowing paragraph, no lists, no markdown.`,
    example: `Edge lighting, medium close-up shot, daylight, left-heavy composition. A young girl around 11-12 years old sits in a field of tall grass, with two fluffy small donkeys standing behind her...`,
    settings: { Resolution: "1280×704 or 704×1280 (720p)", Frames: "121 @ 24 fps (~5 s)", "Guide scale": "5", "Sample shift": "5", Steps: "50 (default)", Note: "I2V: aspect follows the input image" },
    sources: [SRC.wan22Readme, SRC.wan22Sys, SRC.wan22TI2V, SRC.wan21Cfg],
  },
  {
    id: "wan22-14b-t2v", group: "wan", name: "Wan 2.2 · T2V A14B", short: "Wan 2.2 14B T2V",
    type: "video", modes: ["t2v"], words: [60, 200], negative: true, audio: false,
    lengthNote: "60–200 words, from Wan 2.2's official T2V-A14B prompt-extension system prompt.",
    defaultNegative: { en: WAN_NEG_EN, zh: WAN_NEG_ZH },
    guide: `Wan 2.2 T2V A14B (Mixture-of-Experts, 480p/720p). Follow the official Wan 2.2 T2V prompt-extension rules exactly:
- Open with NO MORE THAN 4 cinematic aesthetic tags chosen only from Wan 2.2's official vocabulary, written as a short comma list ending in a full stop, e.g. "Dawn time, top lighting, high-angle shot, cool colors." Vocabulary: time (Day time, Night time, Dawn time, Sunrise time — default Day time); light source (Daylight, Artificial lighting, Moonlight, Practical lighting, Firelight, Fluorescent lighting, Overcast lighting, Sunny lighting — name the source, e.g. window, lamp); intensity (Soft lighting, Hard lighting); tone (Warm colors, Cool colors, Mixed colors); light angle (Top lighting, Side lighting, Underlighting, Edge lighting); shot size (Medium shot, Medium close-up shot, Wide shot, Medium wide shot, Close-up shot, Extreme close-up shot, Extreme wide shot — default Medium or Wide shot); angle (Over-the-shoulder shot, Low angle shot, High angle shot, Dutch angle shot, Aerial shot, Overhead shot — omit if a camera move is described); composition (Center composition default, Balanced, Right-heavy, Left-heavy, Symmetrical, Short-side composition).
- If the user specifies a style, put the style description FIRST; if it is 2D illustration/anime or otherwise non-photographic, do not add film-aesthetic tags. If no style is given, add none.
- Flesh out the subject (appearance, expression, number, ethnicity, posture) and the background detail. Do NOT add subjects that are not in the idea (no people in a landscape prompt).
- Describe the action's process in detail; if there is no action, add a fitting one and add background motion (clouds drifting, wind in leaves).
- No literary atmosphere sentences ("the image is full of tension"). If the sky appears, describe a clear deep-blue sky to avoid overexposure.
- English, one flowing paragraph, no lists, no markdown, no "Rewritten prompt:" label.`,
    example: `Right-heavy composition, warm colors, night time, firelight, over-the-shoulder angle. An eye-level close-up of a woman indoors wearing brown clothes with a colorful necklace and pink hat. She sits on a charcoal-gray chair, hands on a black table, eyes looking left of camera while her mouth moves and her left hand gestures up and down. White candles with yellow flames sit on the table.`,
    settings: { Resolution: "1280×720 or 832×480", Frames: "81 @ 16 fps (~5 s)", "Guide scale": "3.0 low-noise / 4.0 high-noise", "Sample shift": "12", Steps: "40", Boundary: "0.875 (expert switch)" },
    sources: [SRC.wan22Readme, SRC.wan22Sys, SRC.wan22T2V, SRC.wan21Cfg],
  },
  {
    id: "wan22-14b-i2v", group: "wan", name: "Wan 2.2 · I2V A14B", short: "Wan 2.2 14B I2V",
    type: "video", modes: ["i2v"], words: [20, 100], negative: true, audio: false,
    lengthNote: "≤100 words, from Wan 2.2's official I2V-A14B prompt-extension system prompt.",
    defaultNegative: { en: WAN_NEG_EN, zh: WAN_NEG_ZH },
    guide: `Wan 2.2 I2V A14B animates a start image (480p/720p; aspect follows the image). Follow the official Wan 2.2 I2V prompt-extension rules exactly:
- Focus on DYNAMIC content only: the main subject's actions and how they unfold. Simplify the subject to a short reference ("a man", "a black squirrel") — do not re-describe clothing, background or anything static the image already shows.
- If only an action is given ("dancing"), supplement it with a subject from the image ("a girl is dancing").
- Keep and emphasise camera movement in plain words: "the camera pans up", "the camera moves from left to right", "the camera pulls back", "the camera moves left, then pushes forward".
- Chain the action as a process: "They start lying on the ground, then the camera moves upward as they stand up."
- 100 words or less. English. One paragraph, no lists, no markdown.`,
    example: `The camera pulls back to show two men walking up the stairs. The man on the left supports the man on the right with his right hand.`,
    settings: { Resolution: "1280×720 or 832×480 area (aspect = input)", Frames: "81 @ 16 fps (~5 s)", "Guide scale": "3.5 / 3.5", "Sample shift": "5", Steps: "40", Boundary: "0.900 (expert switch)" },
    sources: [SRC.wan22Readme, SRC.wan22Sys, SRC.wan22I2V, SRC.wan21Cfg],
  },

  // ---------- SOCIALSIGHT VIDEO ----------
  {
    id: "seedance-25", group: "ss-video", name: "Seedance 2.5", short: "Seedance 2.5",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [50, 220], negative: false, negInline: true, audio: true, multishot: true, structured: true,
    lengthNote: "No hard text limit published; the guide recommends a complete action + camera + sound arc (≈8 s for a basic prompt).",
    guide: `Seedance 2.5 guide — use its four-part structure, each part on its own line:
1) ASSET MAPPING (only for image/reference modes): name each uploaded asset in upload order and what it controls, and what must NOT be inherited, e.g. "Image 1: Character A's appearance. Video 1: reference only the orbiting camera and editing rhythm. Image 2: reference only the warm golden backlight; do not reference the people."
2) ONE-SENTENCE BRIEF: subject, location, event, genre/style, special camera treatment.
3) TIMELINE: continuous time ranges with no gaps, e.g. "0–3 seconds: ... 3–8 seconds: ..." — each range gives visuals, action, camera move, dialogue and sound. Don't overload a range (actions get dropped) or leave it empty (the model improvises).
4) GLOBAL CONSTRAINTS: rules for the whole clip, e.g. "No subtitles. No background music. No text, flicker or element deformation."
- Standard camera terms work (extreme wide shot, close-up, push-in, pull-out, pan, tilt, low angle, overhead, one-take, aerial, FPV, dolly zoom); for rarer moves describe what the audience sees ("rack focus: the glass in the foreground softens while the woman behind comes into focus").
- Describe emotion as visible facial change, not abstract mood. Few adjectives.`,
    audioRule: `Seedance 2.5 has no special audio tags: state sound in the timeline or constraints in plain words, e.g. "Use only wind, grass rustle and soft rolling sounds. No music." For dialogue, write who speaks and the line, and state the language ("Dialogue is natural English").`,
    multiRule: `Multi-shot = the timeline: continuous ranges "0–3 seconds: ... 3–7 seconds: ... 7–15 seconds: ..." with a different framing per range.`,
    example: `Realistic nature-documentary look on a forest slope in warm afternoon light. A small, round panda cub tumbles clumsily downhill.`,
    settings: { Duration: "up to 30 s", References: "up to 30 images / 10 videos / 10 audio (50 total)", Aspect: "16:9 · 9:16 (unlocked tasks)", Audio: "Native" },
    sources: [SRC.seedance25],
  },
  {
    id: "seedance-20", group: "ss-video", name: "Seedance 2.0 / Fast / Mini", short: "Seedance 2.0",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [50, 200], negative: false, negInline: true, audio: true, multishot: true,
    lengthNote: "No hard limit in the official guide; range chosen to fit its 8-part formula.",
    guide: `Official BytePlus Seedance 2.0 formula, in this order: precise subject + action details + scene + lighting & colour tone + camera movement + visual style + image quality + constraints.
- Actions: name the body part, quantify speed and force ("slowly raises her right hand to shoulder height"); prefer gentle, continuous motion — avoid sprinting, big jumps and fast complex choreography.
- One camera movement per shot.
- References: "Reference <the subject> in Image 1", "Refer to the [camera movement / action] from Video 1". Name assets Image 1, Image 2, Video 1… 4–5 assets is the recommended maximum.
- No negative field: write constraints inline at the end, e.g. "Avoid generating any text or subtitles. Do not generate a watermark."
- Fast/Mini: keep it shorter and simpler.`,
    audioRule: `Seedance 2.0 audio syntax (official): dialogue inside curly braces {…} with the speaker named before it, e.g. The old man says {We should head back.}; background music in full-width parentheses （soft piano music）; sound effects in angle brackets <door creaks open>; on-screen subtitles in 【…】 only if asked. Do not mix languages inside one line of dialogue.`,
    multiRule: `Multi-shot (official): "Shot 1: ... Shot 2: ..." without precise second timings (timing is not reliably followed); one camera move per shot; keep subject descriptions identical across shots.`,
    settings: { Duration: "multi-clip track completion ≤15 s total", References: "4–5 assets recommended", Subtitles: "landscape less likely to add subtitles than portrait", Audio: "Native" },
    sources: [SRC.seedance20],
  },
  {
    id: "kling-3", group: "ss-video", name: "Kling 3.0", short: "Kling 3.0",
    type: "video", modes: ["t2v", "i2v"], words: [40, 220], negative: true, audio: true, multishot: true, structured: true, maxChars: 2500,
    lengthNote: "Kling API: prompt max 3072 characters, ≤2500 recommended (enforced here).",
    defaultNegative: { en: "blur, distort, and low quality", zh: "模糊，扭曲，低质量" },
    guide: `Official Kling order: subject → visible action → scene → camera → lighting & mood. Write it as scene direction, not a list of objects.
- Give each character a unique, consistent label and key traits at first mention, and never switch to pronouns or synonyms for them.
- Describe the camera's relationship to the subject over time ("tracking at medium distance, holds still when she pauses, then resumes"). Supported shot language: profile shot, macro close-up, tracking shot, POV, shot-reverse-shot, slow dolly push-in, orbit, crane.
- Image-to-video: treat the image as the anchor; describe how the scene evolves (subtle movement, camera motion, environment changes). Don't re-describe the image.
- On-screen text: give the exact words and their placement.`,
    audioRule: `Kling native audio (Chinese, English, Japanese, Korean, Spanish). Put the action first, then the line, one speaker per line: [Character A: Lead Detective, controlled serious voice]: "Let's stop pretending." Use the same label every time. Link speakers with timing words ("Immediately,", "Pause.", "Then") so voices don't merge. Sound effects and music are plain scene sentences: "Paper scraping sound. A sad piano chord enters quietly."`,
    multiRule: `Multi-shot (up to 6 shots / 15 s): label every shot with its framing, e.g. "[Shot 1: Wide shot] ... [Shot 2: Medium shot] ...", each with subject, action, camera and approximate duration; keep character labels identical.`,
    example: `A quiet park bench in the late afternoon.\nBirds chirping. Wind through trees.\nSoft acoustic guitar music.`,
    settings: { Durations: "up to 15 s", Resolution: "1080p (4K mode available)", CFG: "0.5 (API default)", Audio: "Native (5 languages)", "Prompt limit": "≤2500 chars" },
    sources: [SRC.klingGuide, SRC.klingApi, SRC.klingFal],
  },
  {
    id: "kling-omni", group: "ss-video", name: "Kling Omni (O3 / 3.0 Omni)", short: "Kling Omni",
    type: "video", modes: ["ref", "i2v", "t2v"], words: [30, 180], negative: true, audio: true, multishot: true, maxChars: 2500,
    lengthNote: "Kling API: prompt max 3072 characters, ≤2500 recommended.",
    defaultNegative: { en: "blur, distort, and low quality", zh: "模糊，扭曲，低质量" },
    guide: `Kling 3.0 Omni is reference-driven. Use Kling's official Omni tags inside the prompt:
- <<<element_1>>> = a character/object element (keeps identity), <<<image_1>>> = a style image or start frame, <<<video_1>>> = motion or performance reference, <<<voice_1>>> = a voice bound to a character. Number them in upload order.
- State the composition and action first, then camera and lighting (Kling order: subject → action → scene → camera → lighting & mood).
- For edits, phrase as instructions: "Keep <<<element_1>>>'s face and outfit; change the setting to a rainy night street."
- Keep each element's name/label identical everywhere it appears.`,
    audioRule: `Bind voices with <<<voice_1>>> next to the character; write lines as [<<<element_1>>>, calm tone]: "Line." and link speakers with timing words. SFX/music as plain sentences.`,
    multiRule: `Multi-shot: "[Shot 1: Wide shot] ... [Shot 2: Close-up] ..." keeping element tags identical in every shot.`,
    settings: { Durations: "up to 15 s", Inputs: "elements, images, video, voices", Audio: "Native", "Prompt limit": "≤2500 chars" },
    sources: [SRC.klingOmni, SRC.klingApi, SRC.klingGuide],
  },
  {
    id: "veo-31", group: "ss-video", name: "Veo 3.1", short: "Veo 3.1",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [60, 180], negative: true, audio: true, multishot: true,
    lengthNote: "No hard limit published; the guide's examples are one to three dense sentences per shot.",
    defaultNegative: { en: "", zh: "" },
    guide: `Google's official Veo 3.1 formula: [Cinematography] + [Subject] + [Action] + [Context] + [Style & Ambiance].
- Lead with camera work and shot composition (e.g. "Medium shot", "Crane shot starting low and rising", "Low-angle tracking shot"), plus lens/focus terms when useful (shallow depth of field, wide-angle, macro).
- Subject with distinctive details, then one clear action, then setting, then lighting and style ("shot as if on 1980s color film, slightly grainy").
- Use specific camera vocabulary: static, pan, tilt, dolly, truck, pedestal, zoom, crane, aerial, handheld, whip pan, arc.
- Ingredients to video (ref mode): describe how the referenced subjects appear together. First/last frame: describe the transition between the two frames.
- Write what you want positively; exclusions go in the separate negative prompt.`,
    audioRule: `Veo 3.1 official audio syntax, in separate sentences after the visuals: dialogue as  A woman says, "We have to leave now."  (short lines that fit the clip); sound effects prefixed "SFX: thunder cracks in the distance."; ambience prefixed "Ambient noise: the quiet hum of a starship bridge."; optional music line.`,
    multiRule: `Multi-shot in one clip (official timestamp prompting): "[00:00-00:02] Medium shot ... [00:02-00:04] Reverse shot ... [00:04-00:06] Close-up ..." — each beat with its own camera and action, within 8 s total.`,
    negRule: `Veo negative prompt: a descriptive list of what to exclude, with no "no"/"don't" wording (e.g. "urban background, man-made structures, dark stormy atmosphere").`,
    example: `Medium shot, a tired corporate worker, rubbing his temples in exhaustion, in front of a bulky 1980s computer in a cluttered office late at night. The scene is lit by the harsh fluorescent overhead lights and the green glow of the monochrome monitor. Retro aesthetic, shot as if on 1980s color film, slightly grainy.`,
    settings: { Durations: "4 / 6 / 8 s", Resolution: "720p / 1080p", Aspect: "16:9 · 9:16", Audio: "Native (dialogue, SFX, ambience, music)" },
    sources: [SRC.veo, SRC.googleVideo],
  },
  {
    id: "sora-2", group: "ss-video", name: "Sora 2 / Sora 2 Pro", short: "Sora 2",
    type: "video", modes: ["t2v", "i2v"], words: [60, 220], negative: false, negInline: true, audio: true, multishot: true, structured: true,
    lengthNote: "No limit published; OpenAI notes shorter prompts give more freedom, longer ones more control.",
    guide: `Use OpenAI's documented Sora 2 template, with these blocks separated by blank lines:
[Prose scene description: characters, costumes, scenery, weather, key visual details. Put the visual style/format early, e.g. "1970s romantic drama, shot on 35 mm film with natural flares".]

Cinematography:
Camera shot: [framing and angle, e.g. wide establishing shot, eye level]
Mood: [overall tone]

Actions:
- [clear, specific beat with timing, e.g. "takes four steps to the window, pauses, pulls the curtain in the final second"]
- [second beat]

- One camera move and one subject action per shot. Replace vague words like "cinematic" with concrete ones (e.g. "anamorphic 2.0x lens, shallow depth of field, warm backlight with soft rim").
- Anchor subjects with distinctive details and reuse the same phrasing for continuity.
- Image-to-video: the image is the first frame; describe what happens next.
- Exclusions go in prose (e.g. "Avoid signage or text.").`,
    audioRule: `Add a "Dialogue:" block after Actions with labelled speakers, e.g.\nDialogue:\n- Detective: "You're lying."\n- Suspect: "Only about the small things."\nKeep it to 1–2 short exchanges per 4 s. Optional "Background Sound:" line for ambience and SFX.`,
    multiRule: `Multi-shot: write each shot as its own block with timing, lens and move, e.g. "0.00–2.40 — Arrival (32 mm, slow dolly left): ..." then "2.40–5.00 — ...", keeping character descriptions identical.`,
    settings: { Durations: "4 / 8 / 12 / 16 / 20 s", "sora-2": "720×1280 · 1280×720", "sora-2-pro": "up to 1080×1920 · 1920×1080", Audio: "Native" },
    sources: [SRC.sora],
  },
  {
    id: "hailuo", group: "ss-video", name: "Hailuo 02 (MiniMax)", short: "Hailuo",
    type: "video", modes: ["t2v", "i2v"], words: [40, 120], negative: false, negInline: false, audio: false,
    lengthNote: "No limit published; MiniMax's own examples are 1–5 sentences.",
    guide: `Hailuo 02 (MiniMax). Formula from MiniMax's examples: scene/subject → action → camera behaviour → transformation or end state, in natural sentences ("The camera executes a rapid push-in from a wide shot, then immediately begins to orbit around the paper...").
- Camera commands can also be written in square brackets inside the prompt: [Truck left], [Truck right], [Pan left], [Pan right], [Push in], [Pull out], [Pedestal up], [Pedestal down], [Tilt up], [Tilt down], [Zoom in], [Zoom out], [Shake], [Tracking shot], [Static shot]. Up to 3 can be combined in one bracket: [Truck left, Pan right, Zoom in]. Place the bracket at the point in the sentence where the move happens.
- Strong at dynamic motion, physics and transformations; describe the end state of the shot.
- Start/end frame (i2v): describe the motion that connects the two frames.
- Silent model: never describe sound.`,
    example: `A woman turns her head to look to the left. A gust of wind blows, sweeping through her hair. With the wind, the woman's clothing and the scene behind her rapidly transform. The camera pulls back to capture the dynamic motion of the woman, now dressed in a Japanese kimono, drawing a sword.`,
    settings: { Durations: "6 / 10 s", Resolution: "768p · 1080p (512p start-frame only)", FPS: "25", "Prompt optimizer": "on by default — turn off for literal prompts" },
    sources: [SRC.hailuoFal, SRC.hailuoNews],
  },
  {
    id: "wan-26", group: "ss-video", name: "Wan 2.6", short: "Wan 2.6",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [50, 130], negative: true, audio: true, multishot: true, structured: true, maxChars: 800, maxNegChars: 500,
    lengthNote: "Hard limits: prompt 800 characters, negative prompt 500 characters (enforced).",
    defaultNegative: { en: "low quality, blurry, distorted, extra fingers, poorly drawn hands, deformed, watermark, subtitles", zh: "低质量，模糊，扭曲，多余的手指，画得不好的手部，畸形的，水印，字幕" },
    guide: `Wan 2.6 (hosted). Structure: one global style/scene line first, then the shots. HARD LIMIT: the whole prompt must be under 800 characters.
- Keep Wan's formula inside each shot: subject + scene + motion + camera language + atmosphere + style.
- Motion intensity words steer energy: "subtle drift" (calm), "smooth track" (moderate), "dynamic sweeping" (energetic).
- Image-to-video: start with "Continue from first frame." and describe motion only; do not re-describe the image.
- Reference-to-video: refer to reference clips as @Video1, @Video2, @Video3 (e.g. "@Video1 walks into the café").`,
    audioRule: `Audio: native sound and lip-sync. Put spoken lines in quotes with the speaker ("@Video1 says: \\"Let's go.\\""), plus a short ambience/music phrase in the relevant shot.`,
    multiRule: `Multi-shot syntax: "Shot 1 [0-3s] wide shot, ... Shot 2 [3-6s] close-up, ..." — timed shots covering the full duration, one per line.`,
    settings: { Durations: "T2V/I2V 5 / 10 / 15 s · R2V 5 / 10 s", Resolution: "720p / 1080p", Aspect: "16:9 · 9:16 · 1:1 · 4:3 · 3:4", Limits: "prompt 800 chars · negative 500 chars" },
    sources: [SRC.wan26],
  },
  {
    id: "google-omni-flash", group: "ss-video", name: "Gemini Omni Flash", short: "Omni Flash",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [40, 160], negative: false, negInline: true, audio: true, multishot: true, structured: true,
    lengthNote: "No prompt limit published (1M-token context). Editing prompts should stay short.",
    guide: `Gemini Omni Flash (gemini-omni-1.1-flash). Official Gemini API guidance:
- Describe scene, camera movement, lighting and mood explicitly ("the camera pans across the mountains"). Vague prompts like "make it move" underperform.
- For a single shot, say so: "In a single continuous shot, no scene cuts."
- Image/reference modes: put source/reference declarations at the START and the guiding instruction at the END, e.g.
  [# Sources <FIRST_FRAME>@Image1] ... Use this image as the starting frame.
  [# References <IMAGE_REF_0>@Image1 <IMAGE_REF_1>@Image2] in the style of <IMAGE_REF_0> a woman <IMAGE_REF_1> is walking ... Use the given images as references for video generation. The images should not be used as literal initial frames.
  (<LAST_FRAME> must be paired with <FIRST_FRAME>; <VIDEO_REF_0> for up to 3 video references ≤3 s each.)
- Edits: keep instructions simple and add "Keep everything else the same."
- No negative-prompt parameter: put exclusions in the prompt ("No embellishments.").`,
    audioRule: `Audio is generated by default. Describe it directly in plain sentences ("Include calm background music.", "The audio is a low tinny radio broadcast in the background."). Dialogue: the man in the red hat says: Where is the rabbit? Exclude with "No dialogue." / "No extra sound effects."`,
    multiRule: `Timing (official): natural phrases ("After 3 seconds, a woman enters the scene.") or timecodes on separate lines: "[0-3s] A person is walking" / "[3-6s] They stop and turn around" / "[6-10s] They start running".`,
    example: `Make the phone invisible. Keep everything else the same.`,
    settings: { Durations: "3–10 s (extend to 40 s)", Resolution: "360p · 720p (default) · 1080p · 4K (upscaled)", FPS: "24", Aspect: "16:9 · 9:16", Audio: "Native" },
    sources: [SRC.omni, SRC.omniModel, SRC.googleVideo],
  },
  {
    id: "happyhorse", group: "ss-video", name: "HappyHorse 1.0", short: "HappyHorse",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [15, 60], negative: false, audio: true, multishot: true,
    lengthNote: "≈20 words per shot is the sweet spot; over 60 words causes face drift and flat motion.",
    guide: `HappyHorse 1.0 (Alibaba) is position-weighted: the START anchors subject and action, the END drives camera and motion.
- Default shape: [Subject] [does action] in [setting], [time of day], [ONE camera or atmosphere cue at the end]. Aim for about 20 words.
- One camera cue per shot (two only if compatible, e.g. "tracking shot with slow dolly-in"). Use concrete terms: Steadicam push, slow dolly-in, lateral orbit with parallax, helicopter aerial, locked-off framing, tracking shot, crane up, whip pan.
- Plain English prose only — no tag lists, JSON or weighted parentheses. No generic adjectives (beautiful, stunning, epic, masterpiece, ultra detailed, hyperrealistic), no stacked synonyms, no director names, no "1000fps".
- Image-to-video: describe only what the image cannot show — motion, sound, light changes, time passing. Never re-describe the image.
- For character consistency, repeat the subject description word-for-word across prompts.
- No negative field; negative cues are mostly wasted words.`,
    audioRule: `Give at least one audio layer: foreground (dialogue in quotes with language named, e.g. dialogue in French: "Bonjour, comment ça va?"), midground (sound tied to visible action: "espresso machine hissing"), background (ambience: "distant street traffic"). Lip-sync languages: English, Mandarin, Cantonese, Japanese, Korean, German, French.`,
    multiRule: `Multi-shot: "Shot 1 (0-2s): wide shot of ..., ambient acoustic guitar. Shot 2 (2-5s): medium tracking shot follows ..., footsteps on hardwood. Shot 3 (5-8s): close-up ..." — each shot ~20 words with its own camera and audio cue. Never write "first X, then Y, then Z" in plain prose.`,
    example: `A grey cat stretched across a linen armchair springs up toward a wooden shelf stacked with books.`,
    settings: { Durations: "5–8 s", Resolution: "up to 1080p", Aspect: "16:9 · 9:16 · 4:3 · 21:9 · 1:1", Audio: "Native" },
    sources: [SRC.hhMorphic, SRC.hhArcads],
  },
  {
    id: "grok-imagine-video", group: "ss-video", name: "Grok Imagine Video 1.5", short: "Grok Imagine",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [30, 150], negative: false, negInline: true, audio: true, multishot: true,
    lengthNote: "xAI publishes no number; over-long prompts return an invalid_argument error.",
    guide: `Grok Imagine Video 1.5 (xAI). Anchor with subject and action first, then scene/environment, camera movement and framing, lighting/colour/grade, then sound.
- Describe camera moves concretely: "The camera arcs a quarter of the way around her.", "A low camera tracks the front wheel arch.", "The camera cranes up slowly."
- Decide everything you care about (visuals AND sound) — unspecified details default to the most average version, and unspecified audio may get music.
- Avoid vague praise ("cinematic masterpiece", "epic", "8k", "stunning"). Don't rely on small rendered text.
- Image-to-video: the image is the first frame. Say what moves and state what must be preserved: "Keep her exact face and the room from the still."
- Reference-to-video (1.5): refer to preset voices as <AUDIO_0>, <AUDIO_1>, <AUDIO_2> (max 3).`,
    audioRule: `Audio is native. Words inside double quotes are spoken with lip-sync — keep lines short and pin them to timecodes. Specify the ambient bed, one or two sound effects that should land, and music or "no music". Describe voice quality ("close and controlled").`,
    multiRule: `Time-coded beats (followed more reliably than prose): "(0-3s) ... (3-6s) Cut to ... (6-9s) Cut to ...".`,
    example: `A matte-silver grand tourer carves through a rain-soaked tunnel at night, rows of sodium lights streaking overhead and smearing across the wet bodywork, spray fanning off the rear tires.`,
    settings: { Durations: "1–15 s", Resolution: "480p (default) · 720p · 1080p (T2V/I2V on 1.5)", Aspect: "16:9 · 9:16 · 1:1 · 4:3 · 3:4 · 3:2 · 2:3", Audio: "Native (generate_audio=false for silent)" },
    sources: [SRC.grokVideo, SRC.grokFal],
  },
  {
    id: "flux3-video", group: "ss-video", name: "FLUX 3 (video)", short: "FLUX 3 Video",
    type: "video", modes: ["t2v", "i2v"], words: [40, 200], negative: false, negInline: true, audio: true, multishot: true, structured: true,
    lengthNote: "BFL publishes no prompt limit; its examples range from one sentence to three short paragraphs.",
    guide: `FLUX 3 video (Black Forest Labs). Official element order:
1) Subject and action — who or what moves and exactly what happens.
2) Camera direction — static shot, slow push-in, handheld follow, overhead drift, rapid pan; lens-specific framing works ("85mm behind the hive into the low sun", "an 800mm shot across a couloir").
3) Scene and atmosphere — environment, lighting, weather, time of day, mood.
4) Motion qualities — slow, abrupt, weightless, chaotic, precise, documentary.
5) Continuity constraints — what must stay stable (essential for image-to-video and continuations).
- Image-to-video: the image sets the first frame; the prompt drives the motion.
- End with explicit exclusions as plain sentences, e.g. "No on-screen text, no logos, no subtitles."`,
    audioRule: `FLUX 3 audio (official): quote the exact spoken words and say who delivers them — a visible speaker ("She looks toward the passenger and says, \\"The 6:10 is delayed again.\\" Low, matter-of-fact delivery.") or an off-screen cue ("An off-screen voiceover says exactly once, \\"...\\""), otherwise quoted words may be drawn as on-screen text. Direct the voice (age/accent, register, recording, delivery). Tie effects to visible actions ("the mug clicks against the saucer"). Give music a style and role ("a sparse piano cue, low in the mix") or say "No music". A compact form is an "Audio:" sentence at the end.`,
    multiRule: `Multi-shot (official): "SHOT ONE: ... HARD CUT. SHOT TWO: ... HARD CUT. SHOT THREE: ..." — or phases within one continuous shot: "Phase 1 (0–3s): ... Phase 2 (3–7s): ...".`,
    example: `Dashcam view of a moose crossing a snowy highway at dusk, wipers sweeping, brake lights reflecting on ice.`,
    settings: { Durations: "5–20 s or auto", Resolution: "hd (default) · fhd · qhd · uhd", Aspect: "auto · 21:9 · 2:1 · 16:9 · 4:3 · 1:1 · 3:4 · 9:16", Audio: "Native with lip-sync" },
    sources: [SRC.flux3, SRC.flux3Audio, SRC.flux3Api],
  },
  {
    id: "runway", group: "ss-video", name: "Runway Gen-4", short: "Runway",
    type: "video", modes: ["i2v"], words: [10, 60], negative: false, audio: false, maxChars: 1000,
    lengthNote: "Hard limit 1000 characters; Runway recommends starting simple.",
    guide: `Runway Gen-4 is image-to-video: the input image defines subject, composition and style. Official guidance:
- Describe MOTION only, in this order as needed: subject motion, camera motion, scene motion, then optional style descriptors ("cinematic live-action", "handheld").
- Refer to the subject generically ("the subject", "the woman") — do not restate what the image shows.
- Positive phrasing only: never write negatives ("no blur") — they can cause the opposite.
- No conversational or command language ("can you please make...").
- One scene per clip; keep it simple and add detail only if needed.
- Silent model: never describe sound.`,
    example: `a handheld camera tracks the mechanical bull as it runs across the desert. the movement disturbs dust that trails behind the mechanical creature. cinematic live-action.`,
    settings: { Durations: "5 / 10 s", Input: "Image required", "Prompt limit": "1000 chars" },
    sources: [SRC.runway, SRC.runwayCreate],
  },

  // ---------- SOCIALSIGHT IMAGE ----------
  {
    id: "nano-banana-pro", group: "ss-image", name: "Nano Banana Pro", short: "Nano Banana Pro",
    type: "image", modes: ["t2i", "edit"], words: [30, 150], negative: false, negInline: true, audio: false,
    lengthNote: "Google publishes no length limit; it recommends full descriptive sentences.",
    guide: `Nano Banana Pro (Gemini 3 Pro Image). Google's official elements: Subject (specific) → Composition (framing, aspect ratio) → Action → Location → Style, plus camera and lighting details (angle, focus, depth of field, colour grading, lighting).
- Write natural full sentences, not keyword lists.
- Text: give the exact words in quotes and describe font, colour and placement, e.g. The headline 'URBAN EXPLORER' rendered in bold, white, sans-serif font at the top.
- Infographics/diagrams: state factual constraints (accuracy) and give correct facts.
- Edits: direct, specific instructions ("change the man's tie to green", "remove the car in the background").
- Multiple references (up to 14): give each image a role — "Use Image A for the character's pose, Image B for the art style, and Image C for the background."`,
    example: `Turn this scene into nighttime`,
    settings: { Resolution: "1K · 2K · 4K", Aspect: "1:1 · 16:9 · 9:16 · 21:9 and more", References: "up to 14 images" },
    sources: [SRC.nano],
  },
  {
    id: "gpt-image-2", group: "ss-image", name: "GPT Image 2", short: "GPT Image 2",
    type: "image", modes: ["t2i", "edit"], words: [30, 180], negative: false, negInline: true, audio: false, structured: true,
    lengthNote: "No limit published; OpenAI recommends a clean base prompt refined by small single-change follow-ups.",
    guide: `OpenAI's image prompting guide for gpt-image-2:
- Order: background/scene → subject → key details → constraints. State the intended use (ad, UI mockup, infographic) to set the polish level.
- For complex requests use short labelled sections or line breaks instead of one long paragraph.
- Specify medium (photo, watercolor, 3D render), materials/textures, framing and viewpoint, angle, lighting and mood. For photorealism, say "photorealistic".
- Layout: explicit placement ("logo top-right", "subject centered with negative space on left"); for people give scale, framing, gaze, pose.
- Text: put literal text in quotes or ALL CAPS, specify font, size, colour, placement; spell tricky words letter by letter; ask for verbatim text, once, no extra characters.
- Constraints as explicit lines: "no watermark", "no extra text", "no logos".
- Edits: "Change only X. Keep everything else the same." and repeat the preserve list. Multi-image: "Image 1: product photo… Image 2: style reference… Apply Image 2's style to Image 1."`,
    example: `Translate the text in the infographic to Spanish. Do not change any other aspect of the image.`,
    settings: { Sizes: "1024×1024 · 1536×1024 · 1024×1536 · 2560×1440 (any, edges ×16, ≤3:1)", Quality: "low / medium / high (high for dense text)", Transparency: "background=transparent + png/webp" },
    sources: [SRC.gptImage],
  },
  {
    id: "flux-2-pro", group: "ss-image", name: "FLUX.2 Pro", short: "FLUX.2 Pro",
    type: "image", modes: ["t2i", "edit"], words: [30, 80], negative: false, audio: false,
    lengthNote: "BFL: 30–80 words is ideal for most projects (10–30 short, 80+ for complex scenes).",
    guide: `Official FLUX.2 guide: Subject + Action + Style + Context. Word order matters — FLUX.2 weights what comes first, so order: main subject → key action → critical style → essential context → secondary details.
- FLUX.2 does NOT support negative prompts: describe what you want ("sharp focus throughout" instead of "no blur", "an empty street" instead of "no people"). Never write "no ...".
- Text: put the words in quotes with placement, typography, size and colour: The text 'OPEN' appears in red neon letters above the door.
- Colours: bind hex codes to specific objects ("The car is color #FF0000"), not "use #FF0000 somewhere".
- Multi-reference edits: state each input's role ("subject from image 1, style from image 2, background from image 3").`,
    example: `Black cat hiding behind a watermelon slice, professional studio shot, bright red and turquoise background with summer mystery vibe`,
    settings: { Resolution: "up to 4MP (≤2MP recommended), multiples of 16", Aspect: "1:1 · 16:9 · 9:16 · 4:3 · 21:9", References: "[pro] up to 8", "Prompt upsampling": "optional" },
    sources: [SRC.flux2],
  },
  {
    id: "seedream-5", group: "ss-image", name: "Seedream 5.0 Pro", short: "Seedream 5",
    type: "image", modes: ["t2i", "edit"], words: [30, 120], negative: false, audio: false,
    lengthNote: "No limit published.",
    guide: `Seedream 5.0 Pro (ByteDance) — SPACE checklist, in order:
S Subject — who/what is in frame, concretely.
P Palette & style — art direction, medium, mood.
A Arrangement — composition, framing, layout.
C Camera & light — lens, angle, light direction and quality.
E Extra detail — on-image text, textures, finishing.
- Put exact on-image words in quotation marks (unquoted text may render garbled). Native text in 14 languages.
- Name what is in frame and how it is arranged rather than relying on one adjective like "beautiful".
- Edits: change one element and say the rest (lighting, layout) stays the same; point to the exact target.`,
    example: `A weathered fisherman mending a net`,
    settings: { Strengths: "dense layouts, infographics, multilingual text", Editing: "point / box / arrow / sketch targeting" },
    sources: [SRC.seedream],
  },
  {
    id: "qwen-image-3", group: "ss-image", name: "Qwen Image 3.0", short: "Qwen Image 3",
    type: "image", modes: ["t2i", "edit"], words: [30, 150], negative: true, audio: false,
    lengthNote: "No limit published; short prompts leave more decisions to the model.",
    defaultNegative: { en: "visible text, signage, watermark, clutter on surfaces, harsh contrast", zh: "可见文字，招牌，水印，杂乱的表面，强烈对比" },
    guide: `Qwen-Image 3.0 guide: lead with the SUBJECT (starting with style makes the subject serve the adjectives), then layer: composition & framing → environmental detail → lighting → style.
- State counts and positions explicitly ("three croissants on a tray under a glass dome", "shelving behind the counter") — avoid "some" / "various".
- Write composition for the chosen canvas shape (aspect ratio changes the composition, not just the crop).
- Strong at lettering in English and Chinese: quote exact text; say "no text" when lettering would be noise.
- Negative prompt: name categories of failure to trim an already-working prompt (people, reflections, clutter, visible text, watermark) — not subjects that were never mentioned.
- Iterate with prompt extension off for reproducibility.`,
    example: `a bakery counter`,
    settings: { Size: "1024×1024 default; 262,144–6,553,600 px area (≤2,250,000 with reference)", References: "1–3 images", "Prompt extend": "off while iterating (seed reproducible)" },
    sources: [SRC.qwen],
  },
  {
    id: "grok-imagine-image", group: "ss-image", name: "Grok Imagine Image 2.0", short: "Grok Image",
    type: "image", modes: ["t2i", "edit"], words: [15, 80], negative: false, audio: false,
    lengthNote: "xAI publishes no prompt rules or limits; its example is a single sentence.",
    guide: `Grok Imagine Image 2.0 (xAI). xAI's docs publish no prompt structure, so use concise, concrete natural language: subject → style/medium → setting → lighting → composition. Its official example is one sentence with a clear style: "A collage of London landmarks in a stenciled street-art style". Put any text in quotes.`,
    example: `A collage of London landmarks in a stenciled street-art style`,
    settings: { Resolution: "1k (default) · 2k", Aspect: "auto · 1:1 · 16:9 · 9:16 · 4:3 · 3:4 · 3:2 · 2:3 · 2:1 · 21:9 · 19.5:9 · 20:9 · 5:2", Quality: "low · medium · auto" },
    sources: [SRC.grokImage],
  },

  // ---------- UNIVERSAL ----------
  {
    id: "universal-video", group: "universal", name: "Any video model (universal)", short: "Universal video",
    type: "video", modes: ["t2v", "i2v", "ref"], words: [60, 150], negative: true, audio: true, multishot: true,
    lengthNote: "Chosen to sit inside every guide above (most recommend one dense paragraph).",
    defaultNegative: { en: WAN_NEG_EN, zh: WAN_NEG_ZH },
    guide: `Write a model-agnostic prompt using the elements every official guide above agrees on:
- Order: camera framing → subject with distinctive details → one clear action with timing → setting → lighting (source, direction, quality) → colour/style. Concrete visual words, no vague praise ("epic", "stunning", "8k").
- One camera move and one main action per shot; physically plausible motion with speed and direction.
- Image-to-video: describe motion and camera only; do not re-describe the image.
- Keep sound in one final sentence starting with "Audio:" so it can be deleted for silent models; put dialogue in double quotes with the speaker named.`,
    multiRule: `Multi-shot: "Shot 1 (0-3s): ... Shot 2 (3-6s): ..." — the timed-shot pattern most models accept.`,
    settings: {},
    sources: [SRC.veo, SRC.sora, SRC.klingGuide, SRC.wan22Sys],
  },
  {
    id: "universal-image", group: "universal", name: "Any image model (universal)", short: "Universal image",
    type: "image", modes: ["t2i", "edit"], words: [30, 100], negative: true, audio: false,
    lengthNote: "Matches FLUX.2's 30–80-word sweet spot, with some room above it.",
    defaultNegative: { en: "low quality, blurry, distorted anatomy, extra fingers, watermark, text artifacts", zh: "低质量，模糊，解剖结构扭曲，多余手指，水印，文字伪影" },
    guide: `Model-agnostic image prompt built on what the official guides share: subject first → action/pose → style/medium → composition & framing → lighting → context. Natural sentences, exact text in quotes with placement, explicit counts and positions. Describe what you want positively in the prompt; exclusions go only in the negative field.`,
    settings: {},
    sources: [SRC.flux2, SRC.gptImage, SRC.nano],
  },
  {
    id: "custom", group: "universal", name: "Custom model…", short: "Custom",
    type: "video", modes: ["t2v", "i2v", "ref", "t2i", "edit"], words: [60, 150], negative: true, audio: true, multishot: true,
    defaultNegative: { en: WAN_NEG_EN, zh: WAN_NEG_ZH },
    guide: `Target is a user-specified model (name given below). Apply that model's documented prompting conventions if you know them; if not, write a strong universal prompt: camera framing → subject → one clear action → setting → lighting → style.`,
    settings: {},
    sources: [],
  },
];

window.MODE_LABELS = {
  t2v: "Text → Video", i2v: "Image → Video", ref: "Reference → Video",
  t2i: "Text → Image", edit: "Image edit",
};
