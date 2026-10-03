"""Template for adding any model. Reference it in config.yaml:

  my-model:
    type: python
    module: ./examples/my_backend.py
    class: MyBackend
    fps: 16
    max_frames: 81
    frame_multiple: 4
    frame_offset: 1
    sizes: ["832*480", "480*832"]
    supports: [t2v, i2v]
    # any extra keys you add here are available as self.cfg[...]
"""
from wanbot.backends import Backend, BackendError, ClipRequest  # noqa: F401
from wanbot.media import write_tensor_video


class MyBackend(Backend):
    def load(self):
        # Load weights once. Example with diffusers:
        # import torch
        # from diffusers import WanPipeline
        # self.pipe = WanPipeline.from_pretrained(self.cfg["repo"], torch_dtype=torch.bfloat16).to("cuda")
        self.pipe = None

    def unload(self):
        self.pipe = None

    def generate(self, req: ClipRequest) -> str:
        # req.prompt, req.negative, req.image (path or None), req.frames, req.width, req.height,
        # req.fps, req.seed, req.steps, req.guide_scale, req.shift, req.output
        #
        # Produce frames and write an mp4 to req.output. Two easy ways:
        #   1) you have a (C,F,H,W) tensor in [-1,1]:  write_tensor_video(tensor, req.output, req.fps)
        #   2) you have a list of PIL images:           from diffusers.utils import export_to_video
        #                                                export_to_video(frames, req.output, fps=req.fps)
        raise BackendError("MyBackend.generate is a template — implement it")
