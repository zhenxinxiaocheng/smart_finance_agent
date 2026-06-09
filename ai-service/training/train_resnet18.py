import argparse
from pathlib import Path

import torch
from sklearn.metrics import classification_report, confusion_matrix
from torch import nn
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms


CLASSES = ["wechat", "alipay", "bank", "non_bill"]


def build_loaders(data_dir: Path, batch_size: int):
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
        DataLoader(train_ds, batch_size=batch_size, shuffle=True, num_workers=2),
        DataLoader(val_ds, batch_size=batch_size, shuffle=False, num_workers=2),
        DataLoader(test_ds, batch_size=batch_size, shuffle=False, num_workers=2),
    )


def build_model(num_classes: int, freeze_backbone: bool):
    weights = models.ResNet18_Weights.DEFAULT
    model = models.resnet18(weights=weights)
    if freeze_backbone:
        for param in model.parameters():
            param.requires_grad = False
    model.fc = nn.Linear(model.fc.in_features, num_classes)
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


def evaluate(model, loader, device, class_names):
    model.eval()
    preds, labels_all = [], []
    with torch.no_grad():
        for images, labels in loader:
            logits = model(images.to(device))
            preds.extend(logits.argmax(dim=1).cpu().tolist())
            labels_all.extend(labels.tolist())
    print(classification_report(labels_all, preds, target_names=class_names, digits=4))
    print("Confusion matrix:")
    print(confusion_matrix(labels_all, preds))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--output", default="models/bill_resnet18.pt")
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--freeze-backbone", action="store_true")
    args = parser.parse_args()

    data_dir = Path(args.data_dir)
    class_ds, train_loader, val_loader, test_loader = build_loaders(data_dir, args.batch_size)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = build_model(len(class_ds.classes), args.freeze_backbone).to(device)
    optimizer = torch.optim.AdamW(filter(lambda p: p.requires_grad, model.parameters()), lr=args.lr)
    criterion = nn.CrossEntropyLoss()

    best_acc = 0.0
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    for epoch in range(1, args.epochs + 1):
        train_loss, train_acc = run_epoch(model, train_loader, criterion, optimizer, device, True)
        val_loss, val_acc = run_epoch(model, val_loader, criterion, optimizer, device, False)
        print(f"epoch={epoch} train_loss={train_loss:.4f} train_acc={train_acc:.4f} val_loss={val_loss:.4f} val_acc={val_acc:.4f}")
        if val_acc >= best_acc:
            best_acc = val_acc
            torch.save({"state_dict": model.state_dict(), "classes": class_ds.classes}, output)

    checkpoint = torch.load(output, map_location=device)
    model.load_state_dict(checkpoint["state_dict"])
    evaluate(model, test_loader, device, class_ds.classes)


if __name__ == "__main__":
    main()
