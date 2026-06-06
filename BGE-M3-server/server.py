from fastapi import FastAPI
from pydantic import BaseModel
from FlagEmbedding import BGEM3FlagModel
from typing import List, Literal
import numpy as np
import torch
import uvicorn

use_fp16 = torch.cuda.is_available()
print(f"BGE-M3 server starting | FP16={use_fp16} | CUDA={torch.cuda.is_available()}")

app = FastAPI(title="BGE-M3 Embedding Service")
model = BGEM3FlagModel("BAAI/bge-m3", use_fp16=use_fp16)
test = model.encode(["init"], return_colbert_vecs=True)
print(f"ColBERT token shape: {test['colbert_vecs'][0].shape} | Dense dim: {test['dense_vecs'][0].shape}")

class EmbedRequest(BaseModel):
    inputs: List[str]
    modes: List[Literal["dense", "sparse", "colbert"]] = ["dense"]
    batch_size: int = 32

@app.post("/embed")
def embed(req: EmbedRequest):
    need_dense = "dense" in req.modes
    need_sparse = "sparse" in req.modes
    need_colbert = "colbert" in req.modes

    output = model.encode(
        req.inputs, batch_size=req.batch_size,
        return_dense=need_dense, return_sparse=need_sparse, return_colbert_vecs=need_colbert
    )

    data = []
    for i in range(len(req.inputs)):
        item = {}
        if need_dense:
            item["dense"] = np.asarray(output["dense_vecs"][i], dtype=np.float32).tolist()
        if need_sparse:
            w = output["lexical_weights"][i]
            item["sparse"] = {
                "indices": np.asarray(list(w.keys()), dtype=np.int64).tolist(),
                "values": np.asarray(list(w.values()), dtype=np.float32).tolist()
            }
        if need_colbert:
            item["colbert"] = np.asarray(output["colbert_vecs"][i], dtype=np.float32).tolist()
        data.append(item)

    return {"model": "bge-m3", "data": data}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8081)
