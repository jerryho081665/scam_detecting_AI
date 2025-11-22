import torch
from transformers import BertTokenizer, BertForSequenceClassification
import torch.nn.functional as F

# Define the directory where you saved your model and tokenizer
output_dir = "./fine_tuned_bert_model"

# Load the tokenizer and model
loaded_tokenizer = BertTokenizer.from_pretrained(output_dir)
loaded_model = BertForSequenceClassification.from_pretrained(output_dir)

# Move the loaded model to the appropriate device (CPU or GPU)
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
loaded_model.to(device)

# Define a prediction function using the loaded model and tokenizer
def predict_sentiment_loaded_model(text):
    loaded_model.eval()  # Set model to evaluation mode
    inputs = loaded_tokenizer(
        text, padding="max_length", truncation=True, max_length=128, return_tensors="pt"
    )
    inputs = {key: val.to(device) for key, val in inputs.items()}

    with torch.no_grad():
        outputs = loaded_model(**inputs)
        logits = outputs.logits

    # Apply softmax to get probabilities
    probabilities = F.softmax(logits, dim=-1)

    # The probability of the 'scam' class is at index 1
    scam_probability = probabilities[0][1].item()  # Assuming batch size of 1

    return scam_probability


# Test with your new examples
print(
    f"'已被列為重點風險對，限時處理逾凍結資產請儘速處理。' -> {predict_sentiment_loaded_model('已被列為重點風險對，限時處理逾凍結資產請儘速處理。'):.4f}"
)
print(
    f"'您好這裡是台銀行客服，想與確認聯絡地址是否確' -> {predict_sentiment_loaded_model('您好這裡是台銀行客服，想與確認聯絡地址是否確'):.4f}"
)
print(
    f"'您好，今天有空一起吃飯嗎謝謝。' -> {predict_sentiment_loaded_model('您好，今天有空一起吃飯嗎謝謝。'):.4f}"
)
print(
    f"'老師帶單，跟單盈利全退費，不賺不收費' -> {predict_sentiment_loaded_model('老師帶單，跟單盈利全退費，不賺不收費'):.4f}"
)
