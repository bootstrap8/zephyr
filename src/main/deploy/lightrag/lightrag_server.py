"""LightRAG sidecar for zephyr knowledge module."""
import asyncio
import os
import shutil
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from lightrag import LightRAG, QueryParam
from lightrag.llm.openai import openai_complete_if_cache, openai_embed
from lightrag.utils import EmbeddingFunc

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("lightrag-sidecar")

# --- config from env ---
DATA_DIR = Path(os.environ["LIGHTRAG_DATA_DIR"])
LLM_BASE_URL = os.environ["LIGHTRAG_LLM_BASE_URL"]
LLM_MODEL = os.environ["LIGHTRAG_LLM_MODEL"]
LLM_API_KEY = os.environ["LIGHTRAG_LLM_API_KEY"]
EMBED_BASE_URL = os.environ["LIGHTRAG_EMBED_BASE_URL"]
EMBED_MODEL = os.environ["LIGHTRAG_EMBED_MODEL"]
EMBED_API_KEY = os.environ["LIGHTRAG_EMBED_API_KEY"]
EMBED_DIM = int(os.environ["LIGHTRAG_EMBED_DIM"])

DATA_DIR.mkdir(parents=True, exist_ok=True)

log.info("LightRAG config: DATA_DIR=%s LLM_BASE_URL=%s LLM_MODEL=%s EMBED_BASE_URL=%s EMBED_MODEL=%s EMBED_DIM=%s",
         DATA_DIR, LLM_BASE_URL, LLM_MODEL, EMBED_BASE_URL, EMBED_MODEL, EMBED_DIM)

# --- per-KB RAG instances ---
_rags: dict[str, LightRAG] = {}
_initialized: set[str] = set()
_lock = asyncio.Lock()


async def _get_rag(kb_id: str) -> LightRAG:
    if kb_id not in _rags:
        async with _lock:
            if kb_id not in _rags:
                working_dir = DATA_DIR / kb_id
                working_dir.mkdir(parents=True, exist_ok=True)

                async def llm_func(prompt, system_prompt=None, history_messages=None, **kwargs):
                    return await openai_complete_if_cache(
                        LLM_MODEL, prompt, system_prompt=system_prompt,
                        history_messages=history_messages or [],
                        base_url=LLM_BASE_URL, api_key=LLM_API_KEY, **kwargs
                    )

                async def embed_func(texts: list[str]) -> list[list[float]]:
                    # openai_embed 装饰器默认 embedding_dim=1536，调用 .func 绕过
                    return await openai_embed.func(
                        texts, model=EMBED_MODEL, base_url=EMBED_BASE_URL,
                        api_key=EMBED_API_KEY, embedding_dim=EMBED_DIM
                    )

                rag = LightRAG(
                    working_dir=str(working_dir),
                    llm_model_func=llm_func,
                    embedding_func=EmbeddingFunc(
                        embedding_dim=EMBED_DIM,
                        max_token_size=8192,
                        func=embed_func
                    ),
                    llm_model_max_async=8
                )
                await rag.initialize_storages()
                _rags[kb_id] = rag
                log.info("LightRAG instance initialized for kb=%s", kb_id)
    return _rags[kb_id]


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    _rags.clear()


app = FastAPI(title="LightRAG Sidecar", lifespan=lifespan)


class IndexRequest(BaseModel):
    doc_id: str
    text: str


class SearchRequest(BaseModel):
    query: str
    mode: str = "hybrid"
    top_k: int = 10


class SearchResult(BaseModel):
    content: str
    source: str
    score: float


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/index/{kb_id}")
async def index_doc(kb_id: str, req: IndexRequest):
    try:
        rag = await _get_rag(kb_id)
        tagged = f"[doc:{req.doc_id}]\n{req.text}"
        await rag.ainsert(tagged)
        log.info("indexed doc=%s into kb=%s", req.doc_id, kb_id)
        return {"status": "ok"}
    except Exception as e:
        log.exception("index failed: kb=%s doc=%s", kb_id, req.doc_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/search/{kb_id}")
async def search_kb(kb_id: str, req: SearchRequest):
    try:
        rag = await _get_rag(kb_id)
        param = QueryParam(mode=req.mode, top_k=req.top_k)
        result = await rag.aquery(req.query, param=param)
        if not result:
            return []
        return [SearchResult(content=result, source="graph", score=1.0)]
    except Exception as e:
        log.exception("search failed: kb=%s", kb_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/index/{kb_id}/{doc_id}")
def delete_doc(kb_id: str, doc_id: str):
    try:
        _rags.pop(kb_id, None)
        working_dir = DATA_DIR / kb_id
        if working_dir.exists():
            for f in working_dir.glob(f"*{doc_id}*"):
                f.unlink(missing_ok=True)
        log.info("deleted doc=%s from kb=%s", doc_id, kb_id)
        return {"status": "ok"}
    except Exception as e:
        log.exception("delete doc failed: kb=%s doc=%s", kb_id, doc_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/kb/{kb_id}")
def delete_kb(kb_id: str):
    try:
        _rags.pop(kb_id, None)
        working_dir = DATA_DIR / kb_id
        if working_dir.exists():
            shutil.rmtree(working_dir)
        log.info("deleted kb=%s", kb_id)
        return {"status": "ok"}
    except Exception as e:
        log.exception("delete kb failed: kb=%s", kb_id)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=9621)
