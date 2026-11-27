FROM python:3.12.12-slim
WORKDIR /srv
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY dp30 dp30
COPY migrations migrations
USER 65534:65534
CMD ["uvicorn","dp30.main:app","--host","0.0.0.0","--port","8000","--no-access-log"]
