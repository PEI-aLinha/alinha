"""Request body size limit (413), checked before anything else reads the body.

FastAPI reads the body before running dependencies, so without this a caller without
the service token could still make the service read an arbitrarily large body.
Uvicorn has no body size option, and Nginx is not in front of this internal service.
"""

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from ai_service.errors import error_response


def _too_large():
    return error_response(413, "payload_too_large", "Request body is too large.")


class BodySizeLimit:
    """Buffers the body up to app.state.max_request_bytes, then replays it to the app.

    Memory per request is bounded by the limit. Counting the bytes also covers callers
    that send no Content-Length (chunked) or a false one.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        limit: int = scope["app"].state.max_request_bytes

        declared = dict(scope["headers"]).get(b"content-length")
        if declared is not None and declared.isdigit() and int(declared) > limit:
            await _too_large()(scope, receive, send)
            return

        chunks: list[bytes] = []
        total = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return  # client left; nothing to answer
            body = message.get("body", b"")
            total += len(body)
            if total > limit:
                await _too_large()(scope, receive, send)
                return
            chunks.append(body)
            if not message.get("more_body", False):
                break

        replayed = False

        async def replay() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()  # after the body: wait for disconnect as usual

        await self.app(scope, replay, send)
