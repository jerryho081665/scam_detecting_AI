import torch
import torch.nn.functional as F
from transformers import BertTokenizer, BertForSequenceClassification
from flask import Flask, request, jsonify

app = Flask(__name__)

# --- 1. LOAD THE AI MODEL (Runs once when server starts) ---
print("Loading BERT model... please wait.")
output_dir = "./fine_tuned_bert_model"

try:
    loaded_tokenizer = BertTokenizer.from_pretrained(output_dir)
    loaded_model = BertForSequenceClassification.from_pretrained(output_dir)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    loaded_model.to(device)
    print(f"Model loaded successfully on {device}!")
except Exception as e:
    print(f"Error loading model. Make sure the folder '{output_dir}' exists.")
    print(e)


# --- 2. DEFINE THE PREDICTION FUNCTION ---
def predict_sentiment_loaded_model(text):
    loaded_model.eval()
    inputs = loaded_tokenizer(
        text, padding="max_length", truncation=True, max_length=128, return_tensors="pt"
    )
    inputs = {key: val.to(device) for key, val in inputs.items()}

    with torch.no_grad():
        outputs = loaded_model(**inputs)
        logits = outputs.logits

    probabilities = F.softmax(logits, dim=-1)
    # Index 1 is the 'scam' probability
    scam_probability = probabilities[0][1].item()
    return scam_probability


# --- 3. DEFINE THE FLASK ENDPOINT ---
@app.route("/predict", methods=["POST"])
def predict():
    # Get JSON data sent from Android
    data = request.get_json()

    # Extract the text string
    user_text = data.get("message", "")

    if not user_text:
        return jsonify({"error": "No text provided"}), 400

    # Run the AI model
    probability = predict_sentiment_loaded_model(user_text)

    # Send result back to Android
    response = {
        "text_received": user_text,
        "scam_probability": probability,
        "is_risk": probability > 0.5,  # Simple logic to flag high risk
    }
    return jsonify(response)


if __name__ == "__main__":
    # Listen on all network interfaces
    app.run(host="0.0.0.0", port=5000)
