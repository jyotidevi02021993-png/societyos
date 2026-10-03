import fetch from 'node-fetch';
import 'dotenv/config';

const apiKey = process.env.BEDROCK_API_KEY;

async function callBedrock() {
    try {
        const response = await fetch("https://bedrock-runtime.us-east-1.amazonaws.com/converse", {
            method: "POST",
            headers: {
                "Authorization": `Bearer ${apiKey}`,
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                modelId: "anthropic.claude-3-haiku",
                inputText: "Hello from VS Code!"
            })
        });

        if (!response.ok) {
            throw new Error(`Bedrock API error: ${response.status} ${response.statusText}`);
        }

        const data = await response.json();
        console.log("Bedrock response:", data);
    } catch (error) {
        console.error("Error calling Bedrock:", error.message);
    }
}

callBedrock();
