# akka-http-client-cancellation

Contains a server and a client application with the following behavior:

- The server receives `/test/N` `GET` requests, where `N` is a number
- Every second request takes a very long time (1 hour), while the others are relatively fast (50 ms)
- The client continously makes requests to this server in a 5/second rate
- There is a request-level completion timeout implemented that returns after 190 ms instead of waiting forever for the response

The problem is that there is no way (that I could find) in this system to cancel the request in case of the timeout, so  after a very few requests the whole pool gets stuck, full of slow requests waiting for completion.

If we could cancel the request when the timeout occurs, the expected behavior would be that every second request succeeds.
