import argparse
import json
import random
import shutil
import time
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import torch
from PIL import Image
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from torch import nn
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms


CLASSES = ["alipay", "bank", "non_bill", "wechat"]
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def seed_everything(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def prepare_balanced_split(raw_dir: Path, dataset_dir: Path, sample_per_class: int, seed: int):
    rng = random.Random(seed)
    if dataset_dir.exists():
        shutil.rmtree(dataset_dir)
    for split in ["train", "val", "test"]:
        for class_name in CLASSES:
            (dataset_dir / split / class_name).mkdir(parents=True, exist_ok=True)

    manifest = {}
    for class_name in CLASSES:
        files = sorted(
            p for p in (raw_dir / class_name).iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES
        )
        if len(files) < sample_per_class:
            raise RuntimeError(f"{class_name} only has {len(files)} images, need {sample_per_class}")
        selected = rng.sample(files, sample_per_class)
        rng.shuffle(selected)
        train_end = int(sample_per_class * 0.7)
        val_end = train_end + int(sample_per_class * 0.2)
        split_files = {
            "train": selected[:train_end],
            "val": selected[train_end:val_end],
            "test": selected[val_end:],
        }
        manifest[class_name] = {k: len(v) for k, v in split_files.items()}
        for split, split_items in split_files.items():
            for index, src in enumerate(split_items, 1):
                dst = dataset_dir / split / class_name / f"{class_name}_{index:04d}{src.suffix.lower()}"
                shutil.copy2(src, dst)
    return manifest


def build_loaders(data_dir: Path, batch_size: int, workers: int):
    train_tf = transforms.Compose([
        transforms.Resize((256, 256)),
        transforms.RandomResizedCrop(224, scale=(0.85, 1.0)),
        transforms.ColorJitter(brightness=0.15, contrast=0.15, saturation=0.08),
        transforms.RandomRotation(3),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    eval_tf = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    train_ds = datasets.ImageFolder(data_dir / "train", transform=train_tf)
    val_ds = datasets.ImageFolder(data_dir / "val", transform=eval_tf)
    test_ds = datasets.ImageFolder(data_dir / "test", transform=eval_tf)
    return (
        train_ds,
        DataLoader(train_ds, batch_size=batch_size, shuffle=True, num_workers=workers),
        DataLoader(val_ds, batch_size=batch_size, shuffle=False, num_workers=workers),
        DataLoader(test_ds, batch_size=batch_size, shuffle=False, num_workers=workers),
    )


def set_trainable(model: nn.Module, mode: str, model_name: str):
    if mode == "full":
        for param in model.parameters():
            param.requires_grad = True
        return

    for param in model.parameters():
        param.requires_grad = False

    if mode == "freeze":
        return

    if model_name == "resnet18":
        for param in model.layer4.parameters():
            param.requires_grad = True
    elif model_name.startswith("mobilenet"):
        for param in model.features[-3:].parameters():
            param.requires_grad = True
    elif model_name == "efficientnet_b0":
        for param in model.features[-2:].parameters():
            param.requires_grad = True


def build_model(model_name: str, mode: str, num_classes: int):
    if model_name == "resnet18":
        model = models.resnet18(weights=models.ResNet18_Weights.DEFAULT)
        set_trainable(model, mode, model_name)
        model.fc = nn.Linear(model.fc.in_features, num_classes)
    elif model_name == "mobilenet_v3_small":
        model = models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.DEFAULT)
        set_trainable(model, mode, model_name)
        model.classifier[-1] = nn.Linear(model.classifier[-1].in_features, num_classes)
    elif model_name == "efficientnet_b0":
        model = models.efficientnet_b0(weights=models.EfficientNet_B0_Weights.DEFAULT)
        set_trainable(model, mode, model_name)
        model.classifier[-1] = nn.Linear(model.classifier[-1].in_features, num_classes)
    else:
        raise ValueError(f"unsupported model: {model_name}")
    return model


def run_epoch(model, loader, criterion, optimizer, device, train: bool):
    model.train(train)
    total_loss, total, correct = 0.0, 0, 0
    for images, labels in loader:
        images, labels = images.to(device), labels.to(device)
        with torch.set_grad_enabled(train):
            logits = model(images)
            loss = criterion(logits, labels)
            if train:
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
        total_loss += loss.item() * labels.size(0)
        correct += (logits.argmax(dim=1) == labels).sum().item()
        total += labels.size(0)
    return total_loss / max(total, 1), correct / max(total, 1)


def collect_predictions(model, loader, device):
    model.eval()
    preds, labels_all, probs = [], [], []
    with torch.no_grad():
        for images, labels in loader:
            logits = model(images.to(device))
            prob = torch.softmax(logits, dim=1)
            preds.extend(prob.argmax(dim=1).cpu().tolist())
            labels_all.extend(labels.tolist())
            probs.extend(prob.max(dim=1).values.cpu().tolist())
    return labels_all, preds, probs


def benchmark_inference(model, loader, device):
    model.eval()
    images, _ = next(iter(loader))
    images = images.to(device)
    warmup = min(3, len(images))
    with torch.no_grad():
        for _ in range(warmup):
            _ = model(images[:1])
        if device.type == "cuda":
            torch.cuda.synchronize()
        start = time.perf_counter()
        _ = model(images)
        if device.type == "cuda":
            torch.cuda.synchronize()
        elapsed = time.perf_counter() - start
    return elapsed * 1000 / max(len(images), 1)


def plot_curve(history, output: Path):
    epochs = [item["epoch"] for item in history]
    plt.figure(figsize=(8, 5))
    plt.plot(epochs, [item["train_acc"] for item in history], label="train_acc")
    plt.plot(epochs, [item["val_acc"] for item in history], label="val_acc")
    plt.plot(epochs, [item["train_loss"] for item in history], label="train_loss")
    plt.plot(epochs, [item["val_loss"] for item in history], label="val_loss")
    plt.xlabel("epoch")
    plt.legend()
    plt.tight_layout()
    plt.savefig(output, dpi=160)
    plt.close()


def plot_confusion(cm, class_names, output: Path):
    plt.figure(figsize=(6, 5))
    plt.imshow(cm, cmap="Blues")
    plt.xticks(range(len(class_names)), class_names, rotation=25)
    plt.yticks(range(len(class_names)), class_names)
    for y in range(len(class_names)):
        for x in range(len(class_names)):
            plt.text(x, y, str(cm[y, x]), ha="center", va="center")
    plt.xlabel("Predicted")
    plt.ylabel("True")
    plt.tight_layout()
    plt.savefig(output, dpi=160)
    plt.close()


def save_prediction_grid(data_dir: Path, class_names, labels, preds, probs, output: Path, max_items: int = 20):
    test_files = []
    for class_name in class_names:
        test_files.extend(sorted((data_dir / "test" / class_name).glob("*")))
    rows, cols = 4, 5
    plt.figure(figsize=(15, 11))
    for idx, path in enumerate(test_files[:max_items]):
        image = Image.open(path).convert("RGB")
        plt.subplot(rows, cols, idx + 1)
        plt.imshow(image)
        ok = labels[idx] == preds[idx]
        color = "green" if ok else "red"
        plt.title(f"T:{class_names[labels[idx]]}\nP:{class_names[preds[idx]]} {probs[idx]:.2f}", color=color, fontsize=9)
        plt.axis("off")
    plt.tight_layout()
    plt.savefig(output, dpi=150)
    plt.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", default="ai-service/raw-dataset")
    parser.add_argument("--dataset-dir", default="ai-service/dataset")
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--model", choices=["resnet18", "mobilenet_v3_small", "efficientnet_b0"], required=True)
    parser.add_argument("--mode", choices=["freeze", "partial", "full"], default="freeze")
    parser.add_argument("--sample-per-class", type=int, default=50)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--workers", type=int, default=0)
    args = parser.parse_args()

    seed_everything(args.seed)
    raw_dir = Path(args.raw_dir)
    dataset_dir = Path(args.dataset_dir)
    run_dir = Path(args.run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)

    split_manifest = prepare_balanced_split(raw_dir, dataset_dir, args.sample_per_class, args.seed)
    train_ds, train_loader, val_loader, test_loader = build_loaders(dataset_dir, args.batch_size, args.workers)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = build_model(args.model, args.mode, len(train_ds.classes)).to(device)
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total_params = sum(p.numel() for p in model.parameters())

    optimizer = torch.optim.AdamW(filter(lambda p: p.requires_grad, model.parameters()), lr=args.lr)
    criterion = nn.CrossEntropyLoss()

    best_acc = -1.0
    history = []
    model_path = run_dir / "model.pt"
    started = time.perf_counter()
    for epoch in range(1, args.epochs + 1):
        train_loss, train_acc = run_epoch(model, train_loader, criterion, optimizer, device, True)
        val_loss, val_acc = run_epoch(model, val_loader, criterion, optimizer, device, False)
        history.append({
            "epoch": epoch,
            "train_loss": train_loss,
            "train_acc": train_acc,
            "val_loss": val_loss,
            "val_acc": val_acc,
        })
        print(f"{args.model}/{args.mode} epoch={epoch} train_acc={train_acc:.4f} val_acc={val_acc:.4f}")
        if val_acc >= best_acc:
            best_acc = val_acc
            torch.save({"state_dict": model.state_dict(), "classes": train_ds.classes, "model": args.model}, model_path)

    train_seconds = time.perf_counter() - started
    checkpoint = torch.load(model_path, map_location=device)
    model.load_state_dict(checkpoint["state_dict"])
    labels, preds, probs = collect_predictions(model, test_loader, device)
    cm = confusion_matrix(labels, preds)
    report = classification_report(labels, preds, target_names=train_ds.classes, digits=4, output_dict=True)
    inference_ms = benchmark_inference(model, test_loader, device)
    model_size_mb = model_path.stat().st_size / 1024 / 1024

    metrics = {
        "model": args.model,
        "mode": args.mode,
        "seed": args.seed,
        "device": str(device),
        "classes": train_ds.classes,
        "split": split_manifest,
        "sample_per_class": args.sample_per_class,
        "epochs": args.epochs,
        "best_val_acc": best_acc,
        "test_accuracy": accuracy_score(labels, preds),
        "test_macro_f1": f1_score(labels, preds, average="macro"),
        "classification_report": report,
        "confusion_matrix": cm.tolist(),
        "train_seconds": train_seconds,
        "inference_ms_per_image": inference_ms,
        "model_size_mb": model_size_mb,
        "total_params": total_params,
        "trainable_params": trainable_params,
        "history": history,
        "artifacts": {
            "model": str(model_path),
            "training_curve": str(run_dir / "training_curve.png"),
            "confusion_matrix": str(run_dir / "confusion_matrix.png"),
            "predictions_grid": str(run_dir / "test_predictions_grid.jpg"),
        },
    }

    (run_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    plot_curve(history, run_dir / "training_curve.png")
    plot_confusion(cm, train_ds.classes, run_dir / "confusion_matrix.png")
    save_prediction_grid(dataset_dir, train_ds.classes, labels, preds, probs, run_dir / "test_predictions_grid.jpg")
    print(json.dumps({
        "model": args.model,
        "mode": args.mode,
        "test_accuracy": metrics["test_accuracy"],
        "test_macro_f1": metrics["test_macro_f1"],
        "run_dir": str(run_dir),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
