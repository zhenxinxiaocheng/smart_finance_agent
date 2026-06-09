import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import torch
from PIL import Image
from torchvision import transforms

from train_bill_classifier import build_model


def load_image(path: Path):
    raw = Image.open(path).convert("RGB")
    tf = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    return raw.resize((224, 224)), tf(raw).unsqueeze(0)


def target_layer(model, model_name: str):
    if model_name == "resnet18":
        return model.layer4[-1]
    if model_name == "efficientnet_b0":
        return model.features[-1]
    if model_name == "mobilenet_v3_small":
        return model.features[-1]
    raise ValueError(f"unsupported model: {model_name}")


def generate_cam(model, layer, image_tensor, class_idx, device):
    activations = {}
    gradients = {}

    def forward_hook(_, __, output):
        activations["value"] = output.detach()

    def backward_hook(_, grad_input, grad_output):
        gradients["value"] = grad_output[0].detach()

    h1 = layer.register_forward_hook(forward_hook)
    h2 = layer.register_full_backward_hook(backward_hook)
    try:
        logits = model(image_tensor.to(device))
        score = logits[:, class_idx].sum()
        model.zero_grad()
        score.backward()
        acts = activations["value"]
        grads = gradients["value"]
        weights = grads.mean(dim=(2, 3), keepdim=True)
        cam = (weights * acts).sum(dim=1).relu()
        cam = torch.nn.functional.interpolate(cam.unsqueeze(1), size=(224, 224), mode="bilinear", align_corners=False)
        cam = cam.squeeze().cpu().numpy()
        cam = (cam - cam.min()) / (cam.max() - cam.min() + 1e-8)
        return cam, torch.softmax(logits, dim=1).detach().cpu().numpy()[0]
    finally:
        h1.remove()
        h2.remove()


def save_overlay(raw_image, cam, title, output: Path):
    plt.figure(figsize=(5, 5))
    plt.imshow(raw_image)
    plt.imshow(cam, cmap="jet", alpha=0.42)
    plt.title(title, fontsize=10)
    plt.axis("off")
    plt.tight_layout()
    plt.savefig(output, dpi=180)
    plt.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--model", default="resnet18", choices=["resnet18", "efficientnet_b0", "mobilenet_v3_small"])
    parser.add_argument("--mode", default="partial", choices=["freeze", "partial", "full"])
    parser.add_argument("--dataset-dir", default="ai-service/dataset/test")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-per-class", type=int, default=2)
    args = parser.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    checkpoint = torch.load(args.model_path, map_location=device)
    classes = checkpoint["classes"]
    model = build_model(args.model, args.mode, len(classes)).to(device)
    model.load_state_dict(checkpoint["state_dict"])
    model.eval()
    layer = target_layer(model, args.model)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    dataset_dir = Path(args.dataset_dir)

    generated = []
    for class_name in classes:
        image_paths = sorted((dataset_dir / class_name).glob("*"))[:args.max_per_class]
        for image_path in image_paths:
            raw, tensor = load_image(image_path)
            with torch.no_grad():
                pred_idx = int(model(tensor.to(device)).argmax(dim=1).item())
            cam, probs = generate_cam(model, layer, tensor, pred_idx, device)
            pred_name = classes[pred_idx]
            title = f"true={class_name}, pred={pred_name}, conf={probs[pred_idx]:.2f}"
            out = output_dir / f"{class_name}_{image_path.stem}_gradcam.jpg"
            save_overlay(raw, cam, title, out)
            generated.append(str(out))

    (output_dir / "gradcam_files.txt").write_text("\n".join(generated), encoding="utf-8")
    print(f"generated {len(generated)} Grad-CAM images in {output_dir}")


if __name__ == "__main__":
    main()
