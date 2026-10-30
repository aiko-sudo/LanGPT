"""Public CLI event projection; never treats an unknown event as authorization."""
def apply_cli_event(host, tid, turn, event):
    if not isinstance(event, dict):
        raise ValueError('invalid event shape')
    kind = event.get('type')
    native = event.get('session_id') or (event.get('sessionId') if kind == 'session' else '')
    if native:
        if not isinstance(native, str) or native.startswith('-') or len(native) > 256:
            raise ValueError('invalid native session identity')
        host.threads[tid]['native'] = native
        host.save()

    def publish(item):
        host.save()
        host.emit('item/completed', threadId=tid, turnId=turn['id'], item=item)

    def find(ident):
        return next((x for x in turn['items'] if x['id'] == ident), None)

    if host.runtime == 'claude' and kind == 'assistant':
        message = event.get('message') or {}
        for index, block in enumerate(message.get('content', [])):
            block_kind = block.get('type')
            if block_kind not in ('text', 'tool_use'):
                continue
            identity = block.get('id') if block_kind == 'tool_use' else str(message.get('id') or uuid.uuid4()) + '-' + str(index)
            item_id = turn['id'] + '-' + str(identity)
            if find(item_id):
                continue
            if block_kind == 'text':
                item = dict(id=item_id, type='agentMessage', text=block.get('text', ''))
            else:
                inputs = block.get('input') or {}
                item = dict(id=item_id, type='commandExecution', status='inProgress',
                            command=block.get('name', '') + ' · ' + json.dumps(inputs, ensure_ascii=False))
            turn['items'].append(item)
            publish(item)
    elif host.runtime == 'claude' and kind == 'user':
        for block in (event.get('message') or {}).get('content', []):
            if block.get('type') != 'tool_result':
                continue
            item = find(turn['id'] + '-' + str(block.get('tool_use_id', '')))
            if item:
                item.update(status='failed' if block.get('is_error') else 'completed',
                            aggregatedOutput=json.dumps(block.get('content', ''), ensure_ascii=False))
                publish(item)
    elif host.runtime == 'dsh' and kind in ('thinking', 'tool_call', 'tool_result'):
        item_id = turn['id'] + '-' + str(event.get('callId', 'thinking'))
        item = find(item_id)
        if item is None:
            item = dict(id=item_id, type='reasoning' if kind == 'thinking' else 'commandExecution')
            turn['items'].append(item)
        if kind == 'thinking':
            item['text'] = item.get('text', '') + event.get('text', '')
        elif kind == 'tool_call':
            item.update(command=event.get('tool', ''), status='inProgress',
                        aggregatedOutput=json.dumps(event.get('input', {}), ensure_ascii=False))
        else:
            item.update(status=event.get('status', 'unknown'), aggregatedOutput=event.get('result', ''))
        publish(item)
    elif host.runtime == 'dsh' and kind == 'final':
        item_id = turn['id'] + '-final'
        if find(item_id) is None:
            item = dict(id=item_id, type='agentMessage', text=event.get('text', ''))
            turn['items'].append(item)
            publish(item)
        return True, False, False
    elif host.runtime == 'dsh' and kind == 'error':
        return False, False, True
    elif host.runtime == 'dsh' and kind == 'status' and event.get('phase') == 'turn_end':
        completed = (event.get('reason') or {}).get('kind') == 'completed'
        return False, completed, not completed
    elif host.runtime == 'claude' and kind == 'result':
        return True, False, bool(event.get('is_error')) or bool(event.get('permission_denials'))
    return False, False, False
