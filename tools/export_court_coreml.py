#!/usr/bin/env python3
"""Export the court keypoint PyTorch checkpoint to an iOS Core ML package."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import coremltools as ct
import torch
import torch.nn as nn
from torchvision.models import mobilenet_v3_small


DEFAULT_CHECKPOINT = Path("/Volumes/T9/Documents/Hermi/courts.pt")
DEFAULT_OUTPUT = Path("iosApp/iosApp/Models/Court-Keypoint-FP32.mlpackage")
INPUT_SIZE = 480
NUM_KEYPOINT_VALUES = 28
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


class CourtKeypointCoreML(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.backbone = mobilenet_v3_small(weights=None).features
        self.avgpool = nn.AdaptiveAvgPool2d(1)
        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Linear(576, 256),
            nn.BatchNorm1d(256),
            nn.Hardswish(),
            nn.Dropout(0.2),
            nn.Linear(256, 128),
            nn.BatchNorm1d(128),
            nn.Hardswish(),
            nn.Dropout(0.2),
            nn.Linear(128, NUM_KEYPOINT_VALUES),
        )
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # Match Android CourtDetector: RGB image, pixel/255, ImageNet normalize.
        x = (x - self.mean) / self.std
        x = self.backbone(x)
        x = self.avgpool(x)
        x = self.head(x)
        return torch.sigmoid(x)


def load_model(checkpoint_path: Path) -> CourtKeypointCoreML:
    state_dict = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    model = CourtKeypointCoreML()
    missing, unexpected = model.load_state_dict(state_dict, strict=False)

    allowed_missing = {"mean", "std"}
    unexpected = list(unexpected)
    missing = [key for key in missing if key not in allowed_missing]
    if missing or unexpected:
        raise RuntimeError(f"Checkpoint mismatch: missing={missing}, unexpected={unexpected}")

    model.eval()
    return model


def export_coreml(model: CourtKeypointCoreML, output_path: Path) -> None:
    example_input = torch.zeros(1, 3, INPUT_SIZE, INPUT_SIZE)
    traced = torch.jit.trace(model, example_input)

    if output_path.exists():
        shutil.rmtree(output_path, ignore_errors=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    mlmodel = ct.convert(
        traced,
        source="pytorch",
        inputs=[
            ct.TensorType(
                name="input",
                shape=example_input.shape,
            )
        ],
        outputs=[ct.TensorType(name="keypoints")],
        minimum_deployment_target=ct.target.iOS16,
        convert_to="mlprogram",
        compute_precision=ct.precision.FLOAT32,
    )
    mlmodel.save(output_path)


def validate_coreml(output_path: Path) -> None:
    spec = ct.utils.load_spec(str(output_path))
    if len(spec.description.input) != 1:
        raise RuntimeError("Expected exactly one Core ML input.")
    if len(spec.description.output) != 1:
        raise RuntimeError("Expected exactly one Core ML output.")

    input_desc = spec.description.input[0]
    output_desc = spec.description.output[0]
    if input_desc.name != "input":
        raise RuntimeError(f"Unexpected input name: {input_desc.name}")
    if list(input_desc.type.multiArrayType.shape) != [1, 3, INPUT_SIZE, INPUT_SIZE]:
        raise RuntimeError(f"Unexpected input shape: {list(input_desc.type.multiArrayType.shape)}")
    if output_desc.name != "keypoints":
        raise RuntimeError(f"Unexpected output name: {output_desc.name}")
    if list(output_desc.type.multiArrayType.shape) != [1, NUM_KEYPOINT_VALUES]:
        raise RuntimeError(f"Unexpected output shape: {list(output_desc.type.multiArrayType.shape)}")


def remove_appledouble_files(root: Path) -> None:
    for path in root.rglob("._*"):
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
        else:
            path.unlink(missing_ok=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=DEFAULT_CHECKPOINT,
        help=f"Path to courts.pt. Default: {DEFAULT_CHECKPOINT}",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Output .mlpackage path. Default: {DEFAULT_OUTPUT}",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    checkpoint_path = args.checkpoint.expanduser().resolve()
    output_path = args.output.expanduser()

    if not checkpoint_path.exists():
        raise FileNotFoundError(checkpoint_path)

    model = load_model(checkpoint_path)
    export_coreml(model, output_path)
    remove_appledouble_files(output_path.parent)
    validate_coreml(output_path)
    remove_appledouble_files(output_path.parent)
    print(f"Exported {output_path}")


if __name__ == "__main__":
    main()
