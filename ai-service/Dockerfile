FROM python:3.12-slim

WORKDIR /app

# Install system deps for psycopg binary
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq5 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY migrations/ ./migrations/
COPY app/ ./app/

EXPOSE 8097

CMD ["uvicorn", "app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8097"]
