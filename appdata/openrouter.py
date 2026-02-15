import sys
from openrouter import OpenRouter

# Use a context manager for the client itself
with OpenRouter(
    api_key="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"
) as client:
    # The 'send' method returns a response context manager
    res = client.chat.send(
        model="tngtech/deepseek-r1t2-chimera:free",
        messages=[
            {"role": "user", "content": "how much time a person should spend on sleeping"}
        ],
        stream=True
    )

    # You must enter the response context to begin the stream
    with res as event_stream:
        for event in event_stream:
            # The SDK maps events to choices list
            if event.choices:
                content = event.choices[0].delta.content
                if content:
                    sys.stdout.write(content)
                    sys.stdout.flush()